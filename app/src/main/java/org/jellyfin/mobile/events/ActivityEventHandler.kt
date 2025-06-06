package org.jellyfin.mobile.events

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.bridge.JavascriptCallback
import org.jellyfin.mobile.downloads.DownloadsFragment
import org.jellyfin.mobile.player.ui.PlayerFragment
import org.jellyfin.mobile.player.ui.PlayerFullscreenHelper
import org.jellyfin.mobile.settings.SettingsFragment
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.extensions.addFragment
import org.jellyfin.mobile.utils.removeDownload
import org.jellyfin.mobile.utils.requestDownload
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import timber.log.Timber

class ActivityEventHandler(
    private val webappFunctionChannel: WebappFunctionChannel,
) {
    private val eventsFlow = MutableSharedFlow<ActivityEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    fun MainActivity.subscribe() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                eventsFlow.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun MainActivity.handleEvent(event: ActivityEvent) {
        when (event) {
            is ActivityEvent.ChangeFullscreen -> {
                val fullscreenHelper = PlayerFullscreenHelper(window)
                if (event.isFullscreen) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    fullscreenHelper.enableFullscreen()
                    window.setBackgroundDrawable(null)
                } else {
                    // Reset screen orientation
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    fullscreenHelper.disableFullscreen()
                    // Reset window background color
                    window.setBackgroundDrawableResource(R.color.theme_background)
                }
            }
            is ActivityEvent.LaunchNativePlayer -> {
                val args = Bundle().apply {
                    putParcelable(Constants.EXTRA_MEDIA_PLAY_OPTIONS, event.playOptions)
                }
                supportFragmentManager.addFragment<PlayerFragment>(args)
            }
            is ActivityEvent.OpenUrl -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, event.uri.toUri())
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Timber.e("openIntent: %s", e.message)
                }
            }
            is ActivityEvent.DownloadFile -> {
                lifecycleScope.launch {
                    with(event) { requestDownload(uri, filename) }
                }
            }
            is ActivityEvent.RemoveDownload -> {
                lifecycleScope.launch {
                    with(event) { removeDownload(download, force) }
                }
            }
            ActivityEvent.OpenDownloads -> {
                supportFragmentManager.addFragment<DownloadsFragment>()
            }
            is ActivityEvent.CastMessage -> {
                val action = event.action
                chromecast.execute(
                    action,
                    event.args,
                    object : JavascriptCallback() {
                        override fun callback(keep: Boolean, err: String?, result: String?) {
                            webappFunctionChannel.call(
                                """window.NativeShell.castCallback("$action", $keep, $err, $result);""",
                            )
                        }
                    },
                )
            }
            ActivityEvent.RequestBluetoothPermission -> {
                lifecycleScope.launch {
                    bluetoothPermissionHelper.requestBluetoothPermissionIfNecessary()
                }
            }
            ActivityEvent.OpenSettings -> {
                supportFragmentManager.addFragment<SettingsFragment>()
            }
            ActivityEvent.SelectServer -> {
                mainViewModel.resetServer()
            }
            ActivityEvent.ExitApp -> {
                if (serviceBinder?.isPlaying == true) {
                    moveTaskToBack(false)
                } else {
                    finish()
                }
            }
        }
    }

    fun emit(event: ActivityEvent) {
        eventsFlow.tryEmit(event)
    }
}
