package org.jellyfin.mobile.player

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.PLAYER_EVENT_CHANNEL
import org.jellyfin.mobile.player.interaction.PlayerEvent
import org.jellyfin.mobile.player.interaction.PlayerLifecycleObserver
import org.jellyfin.mobile.player.interaction.PlayerMediaSessionCallback
import org.jellyfin.mobile.player.interaction.PlayerNotificationHelper
import org.jellyfin.mobile.player.mediasegments.MediaSegmentAction
import org.jellyfin.mobile.player.mediasegments.MediaSegmentRepository
import org.jellyfin.mobile.player.queue.QueueManager
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.player.ui.DecoderType
import org.jellyfin.mobile.player.ui.DisplayPreferences
import org.jellyfin.mobile.player.ui.PlayState
import org.jellyfin.mobile.player.ui.playermenuhelper.PlayerMenuHelper
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
import org.jellyfin.mobile.utils.applyDefaultAudioAttributes
import org.jellyfin.mobile.utils.applyDefaultLocalAudioAttributes
import org.jellyfin.mobile.utils.extensions.end
import org.jellyfin.mobile.utils.extensions.scaleInRange
import org.jellyfin.mobile.utils.extensions.start
import org.jellyfin.mobile.utils.extensions.width
import org.jellyfin.mobile.utils.getVolumeLevelPercent
import org.jellyfin.mobile.utils.getVolumeRange
import org.jellyfin.mobile.utils.logTracks
import org.jellyfin.mobile.utils.seekToOffset
import org.jellyfin.mobile.utils.setPlaybackState
import org.jellyfin.mobile.utils.toMediaMetadata
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.operations.DisplayPreferencesApi
import org.jellyfin.sdk.api.operations.HlsSegmentApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Suppress("TooManyFunctions")
class PlayerViewModel(application: Application) : AndroidViewModel(application), KoinComponent, Player.Listener {
    private val apiClient: ApiClient = get()
    private val displayPreferencesApi: DisplayPreferencesApi = apiClient.displayPreferencesApi
    private val playStateApi: PlayStateApi = apiClient.playStateApi
    private val hlsSegmentApi: HlsSegmentApi = apiClient.hlsSegmentApi
    private val userApi: UserApi = apiClient.userApi

    private val lifecycleObserver = PlayerLifecycleObserver(this)
    private val audioManager: AudioManager by lazy { getApplication<Application>().getSystemService()!! }
    val notificationHelper: PlayerNotificationHelper by lazy { PlayerNotificationHelper(this) }

    // Media source handling
    private val trackSelector = DefaultTrackSelector(getApplication())
    val trackSelectionHelper = TrackSelectionHelper(this, trackSelector)
    val queueManager = QueueManager(this)
    val mediaSourceOrNull: JellyfinMediaSource?
        get() = queueManager.getCurrentMediaSourceOrNull()
    private val mediaSegmentRepository: MediaSegmentRepository by inject()

    // ExoPlayer
    private val _player = MutableLiveData<ExoPlayer?>()
    private val _playerState = MutableLiveData<Int>()
    private val _decoderType = MutableLiveData<DecoderType>()
    val player: LiveData<ExoPlayer?> get() = _player
    val playerState: LiveData<Int> get() = _playerState
    val decoderType: LiveData<DecoderType> get() = _decoderType

    // Player Menus
    private var playerMenuHelper: PlayerMenuHelper? = null

    // Media Segments Ask to Skip
    private var askToSkipMediaSegments: List<MediaSegmentDto> = emptyList()

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val eventLogger = EventLogger()
    private var analyticsCollector = buildAnalyticsCollector()
    private val initialTracksSelected = AtomicBoolean(false)
    private var fallbackPreferExtensionRenderers = false
    private var playSpeed = 1f

    private var progressUpdateJob: Job? = null
    private var chapterMarkingUpdateJob: Job? = null
    private var skipMediaSegmentUpdateJob: Job? = null

    /**
     * Returns the current ExoPlayer instance or null
     */
    val playerOrNull: ExoPlayer? get() = _player.value

    private val playerEventChannel: Channel<PlayerEvent> by inject(named(PLAYER_EVENT_CHANNEL))

    val mediaSession: MediaSession by lazy {
        MediaSession(
            getApplication<Application>().applicationContext,
            javaClass.simpleName.removePrefix(BuildConfig.APPLICATION_ID),
        ).apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS or MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
            setCallback(mediaSessionCallback)
            applyDefaultLocalAudioAttributes(AudioAttributes.CONTENT_TYPE_MOVIE)
        }
    }
    private val mediaSessionCallback = PlayerMediaSessionCallback(this)

    private var displayPreferences = DisplayPreferences()
    private var autoPlayNextEpisodeEnabled: Boolean = false

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        // Load display preferences
        viewModelScope.launch {
            var customPrefs: Map<String, String?>? = null
            try {
                val displayPreferencesDto by displayPreferencesApi.getDisplayPreferences(
                    displayPreferencesId = Constants.DISPLAY_PREFERENCES_ID_USER_SETTINGS,
                    client = Constants.DISPLAY_PREFERENCES_CLIENT_EMBY,
                )

                customPrefs = displayPreferencesDto.customPrefs
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load display preferences from API")
            }

            displayPreferences = DisplayPreferences(
                skipBackLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_BACK_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
                skipForwardLength = customPrefs?.get(Constants.DISPLAY_PREFERENCES_SKIP_FORWARD_LENGTH)?.toLongOrNull()
                    ?: Constants.DEFAULT_SEEK_TIME_MS,
            )
        }

        viewModelScope.launch {
            try {
                val userConfig = userApi.getCurrentUser().content.configuration
                autoPlayNextEpisodeEnabled = userConfig?.enableNextEpisodeAutoPlay ?: false
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to load auto play preference")
            }
        }

        // Subscribe to player events from webapp
        viewModelScope.launch {
            for (event in playerEventChannel) {
                when (event) {
                    PlayerEvent.Pause -> mediaSessionCallback.onPause()
                    PlayerEvent.Resume -> mediaSessionCallback.onPlay()
                    PlayerEvent.Stop, PlayerEvent.Destroy -> mediaSessionCallback.onStop()
                    is PlayerEvent.Seek -> playerOrNull?.seekTo(event.duration.inWholeMilliseconds)
                    is PlayerEvent.SetVolume -> {
                        setVolume(event.volume)
                        playerOrNull?.reportPlaybackState()
                    }
                }
            }
        }
    }

    private fun buildAnalyticsCollector() = DefaultAnalyticsCollector(Clock.DEFAULT).apply {
        addListener(eventLogger)
    }

    /**
     * Setup a new [ExoPlayer] for video playback, register callbacks and set attributes
     */
    fun setupPlayer() {
        val renderersFactory = DefaultRenderersFactory(getApplication()).apply {
            setEnableDecoderFallback(true) // Fallback only works if initialization fails, not decoding at playback time
            val rendererMode = when {
                fallbackPreferExtensionRenderers -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            }
            setExtensionRendererMode(rendererMode)
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val decoderInfoList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder,
                )
                // Allow decoder selection only for video track
                if (!MimeTypes.isVideo(mimeType)) {
                    return@setMediaCodecSelector decoderInfoList
                }
                val filteredDecoderList = when (decoderType.value) {
                    DecoderType.HARDWARE -> decoderInfoList.filter(MediaCodecInfo::hardwareAccelerated)
                    DecoderType.SOFTWARE -> decoderInfoList.filterNot(MediaCodecInfo::hardwareAccelerated)
                    else -> decoderInfoList
                }
                // Update the decoderType based on the first decoder selected
                filteredDecoderList.firstOrNull()?.let { decoder ->
                    val decoderType = when {
                        decoder.hardwareAccelerated -> DecoderType.HARDWARE
                        else -> DecoderType.SOFTWARE
                    }
                    _decoderType.postValue(decoderType)
                }

                filteredDecoderList
            }
        }
        _player.value = ExoPlayer.Builder(getApplication(), renderersFactory, get()).apply {
            setUsePlatformDiagnostics(false)
            setTrackSelector(trackSelector)
            setAnalyticsCollector(analyticsCollector)
        }.build().apply {
            addListener(this@PlayerViewModel)
            applyDefaultAudioAttributes(C.AUDIO_CONTENT_TYPE_MOVIE)
        }
    }

    /**
     * Release the current ExoPlayer and stop/release the current MediaSession
     */
    private fun releasePlayer() {
        notificationHelper.dismissNotification()
        mediaSession.isActive = false
        mediaSession.release()
        playerOrNull?.run {
            removeListener(this@PlayerViewModel)
            release()
        }
        _player.value = null
    }

    fun load(jellyfinMediaSource: JellyfinMediaSource, exoMediaSource: MediaSource, playWhenReady: Boolean) {
        val player = playerOrNull ?: return

        player.setMediaSource(exoMediaSource)
        player.prepare()

        initialTracksSelected.set(false)

        val startTime = jellyfinMediaSource.startTime
        if (startTime > Duration.ZERO) player.seekTo(startTime.inWholeMilliseconds)

        applyMediaSegments(jellyfinMediaSource)

        player.playWhenReady = playWhenReady

        mediaSession.setMetadata(jellyfinMediaSource.toMediaMetadata())

        if (jellyfinMediaSource is RemoteJellyfinMediaSource) {
            viewModelScope.launch {
                player.reportPlaybackStart(jellyfinMediaSource)
            }
        }
    }

    private fun startProgressUpdates() {
        if (mediaSourceOrNull != null && mediaSourceOrNull !is RemoteJellyfinMediaSource) return
        progressUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.PLAYER_TIME_UPDATE_RATE)
                playerOrNull?.reportPlaybackState()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
    }

    private fun startChapterMarkingUpdates() {
        chapterMarkingUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.CHAPTER_MARKING_UPDATE_DELAY)
                playerOrNull?.setWatchedChapterMarkings()
            }
        }
    }

    private fun stopChapterMarkingUpdates() {
        chapterMarkingUpdateJob?.cancel()
    }

    private fun startSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob = viewModelScope.launch {
            while (true) {
                delay(Constants.SKIP_MEDIA_SEGMENT_UPDATE_DELAY)
                playerOrNull?.updateSkipMediaSegmentButton()
            }
        }
    }

    private fun stopSkipMediaSegmentUpdates() {
        skipMediaSegmentUpdateJob?.cancel()
    }

    /**
     * Updates the decoder of the [Player]. This will destroy the current player and
     * recreate the player with the selected decoder type
     */
    fun updateDecoderType(type: DecoderType) {
        _decoderType.postValue(type)
        analyticsCollector.release()
        val playedTime = (playerOrNull?.currentPosition ?: 0L).milliseconds
        // Stop and release the player without ending playback
        playerOrNull?.run {
            removeListener(this@PlayerViewModel)
            release()
        }
        analyticsCollector = buildAnalyticsCollector()
        setupPlayer()
        queueManager.getCurrentMediaSourceOrNull()?.startTime = playedTime
        queueManager.tryRestartPlayback()
    }

    private suspend fun Player.reportPlaybackStart(mediaSource: RemoteJellyfinMediaSource) {
        try {
            playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = mediaSource.itemId,
                    playMethod = mediaSource.playMethod,
                    playSessionId = mediaSource.playSessionId,
                    audioStreamIndex = mediaSource.selectedAudioStream?.index,
                    subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                    isPaused = !isPlaying,
                    isMuted = false,
                    canSeek = true,
                    positionTicks = mediaSource.startTime.inWholeTicks,
                    volumeLevel = audioManager.getVolumeLevelPercent(),
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                ),
            )
        } catch (e: ApiClientException) {
            Timber.e(e, "Failed to report playback start")
        }
    }

    private fun Player.setWatchedChapterMarkings() {
        val playbackPosition = currentPosition.milliseconds
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        val chapterMarkings = playerMenuHelper?.chapterMarkings?.markings ?: emptyList()

        startPositions.zip(chapterMarkings).forEach { (pos, marking) ->
            val color = if (playbackPosition >= pos) R.color.jellyfin_accent else R.color.playback_timebar_unplayed
            marking.setColor(color)
        }
    }

    private fun Player.updateSkipMediaSegmentButton() {
        val mediaSegments = askToSkipMediaSegments
        if (mediaSegments.isEmpty()) return

        val playbackPosition = currentPosition.milliseconds
        val currentMediaSegment = mediaSegments.find { seg -> playbackPosition in seg.start..seg.end }
        if (currentMediaSegment != null) {
            playerMenuHelper?.skipMediaSegmentButton?.showSkipSegmentButton(currentMediaSegment)
        } else {
            playerMenuHelper?.skipMediaSegmentButton?.hideSkipSegmentButton()
        }
    }

    private suspend fun Player.reportPlaybackState() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val playbackPosition = currentPosition.milliseconds
        if (playbackState != Player.STATE_ENDED) {
            val stream = AudioManager.STREAM_MUSIC
            val volumeRange = audioManager.getVolumeRange(stream)
            val currentVolume = audioManager.getStreamVolume(stream)
            try {
                playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = mediaSource.itemId,
                        playMethod = mediaSource.playMethod,
                        playSessionId = mediaSource.playSessionId,
                        audioStreamIndex = mediaSource.selectedAudioStream?.index,
                        subtitleStreamIndex = mediaSource.selectedSubtitleStream?.index,
                        isPaused = !isPlaying,
                        isMuted = false,
                        canSeek = true,
                        positionTicks = playbackPosition.inWholeTicks,
                        volumeLevel = (currentVolume - volumeRange.first) * Constants.PERCENT_MAX / volumeRange.width,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback progress")
            }
        }
    }

    private fun reportPlaybackStop() {
        val mediaSource = mediaSourceOrNull as? RemoteJellyfinMediaSource ?: return
        val player = playerOrNull ?: return
        val hasFinished = player.playbackState == Player.STATE_ENDED
        val lastPositionTicks = when {
            hasFinished -> mediaSource.runTime.inWholeTicks
            else -> player.currentPosition.milliseconds.inWholeTicks
        }

        // viewModelScope may already be cancelled at this point, so we need to fallback
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Report stopped playback
                playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(
                        itemId = mediaSource.itemId,
                        positionTicks = lastPositionTicks,
                        playSessionId = mediaSource.playSessionId,
                        liveStreamId = mediaSource.liveStreamId,
                        failed = false,
                    ),
                )

                // Mark video as watched if playback finished
                if (hasFinished) {
                    playStateApi.markPlayedItem(itemId = mediaSource.itemId)
                }

                // Stop active encoding if transcoding
                stopTranscoding(mediaSource)
            } catch (e: ApiClientException) {
                Timber.e(e, "Failed to report playback stop")
            }
        }
    }

    suspend fun stopTranscoding(mediaSource: RemoteJellyfinMediaSource) {
        if (mediaSource.playMethod == PlayMethod.TRANSCODE) {
            hlsSegmentApi.stopEncodingProcess(
                deviceId = apiClient.deviceInfo.id,
                playSessionId = mediaSource.playSessionId,
            )
        }
    }

    private fun applyMediaSegments(jellyfinMediaSource: JellyfinMediaSource) {
        askToSkipMediaSegments = emptyList()

        viewModelScope.launch {
            if (jellyfinMediaSource.item != null) {
                val mediaSegments = mediaSegmentRepository.getSegmentsForItem(jellyfinMediaSource.item)
                val newAskToSkipMediaSegments = mutableListOf<MediaSegmentDto>()

                for (mediaSegment in mediaSegments) {
                    val action = mediaSegmentRepository.getMediaSegmentAction(mediaSegment)

                    when (action) {
                        MediaSegmentAction.SKIP -> addSkipAction(mediaSegment)
                        MediaSegmentAction.ASK_TO_SKIP -> newAskToSkipMediaSegments.add(mediaSegment)
                        MediaSegmentAction.NOTHING -> Unit
                    }
                }

                askToSkipMediaSegments = newAskToSkipMediaSegments
            }
        }
    }

    private fun addSkipAction(mediaSegment: MediaSegmentDto) {
        val player = playerOrNull ?: return

        player
            .createMessage { _, _ ->
                viewModelScope.launch(Dispatchers.Main) {
                    player.seekTo(mediaSegment.end.inWholeMilliseconds)
                }
            }
            // Segments at position 0 will never be hit by ExoPlayer so we need to add a minimum value
            .setPosition(mediaSegment.start.inWholeMilliseconds.coerceAtLeast(1))
            .setDeleteAfterDelivery(false)
            .send()
    }

    // Player controls
    fun play() {
        playerOrNull?.play()
    }

    fun pause() {
        playerOrNull?.pause()
    }

    fun rewind() {
        playerOrNull?.seekToOffset(displayPreferences.skipBackLength.unaryMinus())
    }

    fun fastForward() {
        playerOrNull?.seekToOffset(displayPreferences.skipForwardLength)
    }

    private fun getCurrentChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        return startPositions.findLast { pos -> playbackPosition >= pos }
    }

    private fun getNextChapterStartPosition(chapters: List<ChapterInfo>, playbackPosition: Duration): Duration? {
        val startPositions = chapters.map { c -> c.startPositionTicks.ticks }
        val currentChapterIdx = startPositions.indexOfLast { pos -> playbackPosition >= pos }
        if (currentChapterIdx == -1) return null
        val nextChapterIndex = currentChapterIdx + 1
        return startPositions.getOrElse(nextChapterIndex) { _ -> Duration.INFINITE }
    }

    fun previousChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return

        // Update the playback position to be slightly in the past, to check if we should go back to the beginning of the current
        // chapter or the previous one, if not enough time has elapsed since the start of the current chapter
        val skipToPreviousDuration = Constants.MAX_SKIP_TO_PREV_CHAPTER_MS.milliseconds
        val playbackPosition = currentPosition - skipToPreviousDuration
        // If we'd end up with a negative position then we need to play the previous item
        if (playbackPosition < Duration.ZERO) {
            skipToPrevious()
        } else {
            val seekToPosition = getCurrentChapterStartPosition(chapters, playbackPosition) ?: return
            playerOrNull?.seekTo(seekToPosition.inWholeMilliseconds)
        }
    }

    fun nextChapter() {
        val chapters = mediaSourceOrNull?.item?.chapters ?: return
        val currentPosition = playerOrNull?.currentPosition?.milliseconds ?: return
        val playbackPosition = getNextChapterStartPosition(chapters, currentPosition) ?: return

        if (playbackPosition == Duration.INFINITE) {
            skipToNext()
        } else {
            playerOrNull?.seekTo(playbackPosition.inWholeMilliseconds)
        }
    }

    fun skipToPrevious() {
        val player = playerOrNull ?: return
        when {
            // Skip to previous element
            player.currentPosition <= Constants.MAX_SKIP_TO_PREV_MS -> viewModelScope.launch {
                pause()
                if (!queueManager.previous()) {
                    // Skip to previous failed, go to start of video anyway
                    playerOrNull?.seekTo(0)
                    play()
                }
            }
            // Rewind to start of track if not at the start already
            else -> player.seekTo(0)
        }
    }

    fun skipToNext() {
        viewModelScope.launch {
            queueManager.next()
        }
    }

    fun skipMediaSegment(mediaSegmentDto: MediaSegmentDto?) {
        val player = playerOrNull ?: return
        val mediaSegment = mediaSegmentDto ?: return
        player.seekTo(mediaSegment.end.inWholeMilliseconds + 1)
    }

    fun getStateAndPause(): PlayState? {
        val player = playerOrNull ?: return null

        val playWhenReady = player.playWhenReady
        player.pause()
        val position = player.contentPosition.milliseconds

        return PlayState(playWhenReady, position)
    }

    fun logTracks() {
        playerOrNull?.logTracks(analyticsCollector)
    }

    suspend fun changeBitrate(bitrate: Int?): Boolean {
        return queueManager.changeBitrate(bitrate)
    }

    /**
     * Set the playback speed to [speed]
     *
     * @return true if the speed was changed
     */
    fun setPlaybackSpeed(speed: Float): Boolean {
        val player = playerOrNull ?: return false

        val parameters = player.playbackParameters
        if (parameters.speed != speed) {
            player.playbackParameters = parameters.withSpeed(speed)
            return true
        }
        return false
    }

    fun setPressSpeedUp(isPressing: Boolean, speed: Float): Boolean {
        if (!isPressing) {
            return setPlaybackSpeed(playSpeed)
        }
        val player = playerOrNull ?: return false
        val parameters = player.playbackParameters
        playSpeed = parameters.speed
        return setPlaybackSpeed(speed)
    }

    fun stop() {
        pause()
        reportPlaybackStop()
        releasePlayer()
    }

    private fun setVolume(percent: Int) {
        if (audioManager.isVolumeFixed) return
        val stream = AudioManager.STREAM_MUSIC
        val volumeRange = audioManager.getVolumeRange(stream)
        val scaled = volumeRange.scaleInRange(percent)
        audioManager.setStreamVolume(stream, scaled, 0)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("SwitchIntDef")
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
        val player = playerOrNull ?: return

        // Notify fragment of current state
        _playerState.value = playbackState

        // Initialise various components
        if (playbackState == Player.STATE_READY) {
            if (!initialTracksSelected.getAndSet(true)) {
                trackSelectionHelper.selectInitialTracks()
            }
            mediaSession.isActive = true
            notificationHelper.postNotification()
        }

        // Setup or stop regular progress updates
        if (playbackState == Player.STATE_READY && playWhenReady) {
            startProgressUpdates()
            if (!playerMenuHelper?.chapterMarkings?.markings.isNullOrEmpty()) {
                startChapterMarkingUpdates()
            }
            if (askToSkipMediaSegments.isNotEmpty()) {
                startSkipMediaSegmentUpdates()
            }
        } else {
            stopProgressUpdates()
            stopChapterMarkingUpdates()
            stopSkipMediaSegmentUpdates()
        }

        // Update media session
        var playbackActions = SUPPORTED_VIDEO_PLAYER_PLAYBACK_ACTIONS
        if (queueManager.hasPrevious()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        if (queueManager.hasNext()) {
            playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        mediaSession.setPlaybackState(player, playbackActions)

        // Force update playback state and position
        viewModelScope.launch {
            when (playbackState) {
                Player.STATE_READY, Player.STATE_BUFFERING -> {
                    player.reportPlaybackState()
                }
                Player.STATE_ENDED -> {
                    reportPlaybackStop()
                    if (!autoPlayNextEpisodeEnabled || !queueManager.next()) {
                        releasePlayer()
                    }
                }
            }
        }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        playerOrNull?.setWatchedChapterMarkings()
        playerOrNull?.updateSkipMediaSegmentButton()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (error.cause is MediaCodecDecoderException && !fallbackPreferExtensionRenderers) {
            Timber.e(error.cause, "Decoder failed, attempting to restart playback with decoder extensions preferred")
            playerOrNull?.run {
                removeListener(this@PlayerViewModel)
                release()
            }
            fallbackPreferExtensionRenderers = true
            setupPlayer()
            queueManager.tryRestartPlayback()
        } else {
            _error.postValue(error.localizedMessage.orEmpty())
        }
    }

    override fun onCleared() {
        reportPlaybackStop()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        releasePlayer()
    }

    fun setPlayerMenuHelper(menuHelper: PlayerMenuHelper) {
        playerMenuHelper = menuHelper
    }
}
