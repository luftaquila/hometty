package io.luftaquila.hometty

import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.luftaquila.hometty.resources.R as TextR

@Composable
fun DefaultHomePreference() {
    val context = LocalContext.current
    val roles = remember(context) { context.getSystemService(RoleManager::class.java) }
    fun isDefault() =
        roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true &&
            roles.isRoleHeld(RoleManager.ROLE_HOME)
    var held by remember { mutableStateOf(isDefault()) }
    var attempted by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, roles) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) held = isDefault()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            held = isDefault()
            attempted = true
        }
    fun openSettings() {
        try {
            request.launch(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                request.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            } catch (_: android.content.ActivityNotFoundException) {
                unavailable = true
            }
        }
    }
    SettingsLink(
        stringResource(if (held) TextR.string.home_default_selected else TextR.string.home_default_choose),
        summary = if (held) stringResource(TextR.string.home_default_active) else null,
        glyph = SettingsGlyph.System,
    ) {
        unavailable = false
        if (held || roles?.isRoleAvailable(RoleManager.ROLE_HOME) != true) openSettings()
        else {
            try {
                request.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
            } catch (_: android.content.ActivityNotFoundException) {
                openSettings()
            }
        }
    }
    if (!held && attempted) {
        Text(
            stringResource(TextR.string.home_default_unchanged),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("defaultHomeStatus"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsLink(stringResource(TextR.string.home_default_system_settings)) { openSettings() }
    }
    if (unavailable) Text(stringResource(TextR.string.home_default_unavailable))
}
