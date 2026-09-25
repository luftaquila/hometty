package io.luftaquila.hometty

import android.content.*
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.luftaquila.hometty.platform.PlatformRequest
import io.luftaquila.hometty.resources.R as TextR
import io.luftaquila.hometty.resources.message
import kotlinx.coroutines.launch
import io.luftaquila.hometty.core.Settings
import io.luftaquila.hometty.core.get

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: HomeViewModel by viewModels()
    private val backup: BackupViewModel by viewModels()
    private val fonts: FontViewModel by viewModels()
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { values ->
            model.append(
                if (values.values.all { it })
                    getString(TextR.string.label_permission_granted_retry_the_command)
                else
                    getString(
                        TextR.string
                            .label_permission_denied_other_launcher_features_remain_available
                    )
            )
        }
    private val open =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                    .onFailure { model.showError(message(it)) }
            }
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { HomettyApp(model, FeatureModels(backup, fonts)) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.device.events.collect { request ->
                        try {
                            when (request) {
                                is PlatformRequest.Launch -> startActivity(request.intent)
                                is PlatformRequest.Permission ->
                                    permissions.launch(request.names.toTypedArray())
                                PlatformRequest.OpenDocument -> open.launch(arrayOf("*/*"))
                            }
                        } catch (e: Exception) {
                            model.showError(message(e))
                        }
                    }
                }
                launch {
                    model.settings.collect { settings ->
                        requestedOrientation =
                            when (settings[Settings.orientation]) {
                                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        if (settings[Settings.wallpaper]) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                            window.setBackgroundDrawableResource(android.R.color.transparent)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                            // The terminal's own background, so no other color shows at launch or
                            // while the keyboard moves.
                            window.setBackgroundDrawable(
                                android.graphics.drawable.ColorDrawable(
                                    android.graphics.Color.parseColor(
                                        settings[Settings.background]
                                    )
                                )
                            )
                        }
                        fullscreen = settings[Settings.fullscreen]
                        applySystemBars()
                    }
                }
            }
        }
    }

    private var fullscreen = false

    private fun applySystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        if (fullscreen) {
            // Like T-UI's FLAG_FULLSCREEN, only the status bar goes: with the
            // navigation bar hidden as well, the first back or home gesture would only bring the
            // bars back. A swipe down shows the status bar for a moment; by default it would stay.
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.show(WindowInsetsCompat.Type.navigationBars())
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The notification shade, dialogs and other apps can leave the bars shown.
        if (hasFocus) applySystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) model.navigate(Route.Home)
    }

    override fun onStart() {
        super.onStart()
        model.device.shown = true
        model.foreground(true)
    }

    override fun onStop() {
        model.device.shown = false
        model.foreground(false)
        super.onStop()
    }

}
