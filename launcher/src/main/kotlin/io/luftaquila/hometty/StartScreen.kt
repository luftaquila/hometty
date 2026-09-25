package io.luftaquila.hometty

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.luftaquila.hometty.resources.R as TextR

fun notificationAccessGranted(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** A feature that needs a permission: what it enables, whether it is allowed, how to allow it. */
private data class StartItem(
    val id: String,
    val title: Int,
    val description: Int,
    val permission: String? = null,
    val allowed: (Context) -> Boolean = { context -> granted(context, permission!!) },
    val special: (HomeViewModel) -> Unit = {},
)

private fun startItems(): List<StartItem> = buildList {
    add(
        StartItem(
            "notificationAccess",
            TextR.string.start_notification_access,
            TextR.string.start_notification_access_desc,
            allowed = ::notificationAccessGranted,
            special = {
                it.device.settings(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
        )
    )
    add(
        StartItem(
            "contacts",
            TextR.string.start_contacts,
            TextR.string.start_contacts_desc,
            Manifest.permission.READ_CONTACTS,
        )
    )
    add(
        StartItem(
            "phone",
            TextR.string.start_phone,
            TextR.string.start_phone_desc,
            Manifest.permission.CALL_PHONE,
        )
    )
    add(
        StartItem(
            "location",
            TextR.string.start_location,
            TextR.string.start_location_desc,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    )
    add(
        StartItem(
            "writeSettings",
            TextR.string.start_write_settings,
            TextR.string.start_write_settings_desc,
            allowed = { AndroidSettings.System.canWrite(it) },
            special = { it.device.settings(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, true) },
        )
    )
}

/**
 * Getting started: the default home app and every permission, each with the feature it enables.
 * Opens once on first launch and stays reachable from the settings root.
 */
@Composable
fun StartScreen(model: HomeViewModel) {
    val context = LocalContext.current
    val items = remember { startItems() }
    // Bumped on resume and after each request, so statuses follow changes made elsewhere.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    var denied by remember { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<String?>(null) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            pending?.let { if (!ok) denied = denied + it }
            pending = null
            refresh++
        }
    fun allowed(item: StartItem): Boolean = item.allowed(context)
    val firstRun = model.firstRun
    Column(Modifier.fillMaxSize().testTag("startScreen")) {
        Header(context.getString(TextR.string.start_title), {
            if (firstRun) model.finishStart() else model.back()
        })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionLabel(context.getString(TextR.string.start_section_home))
                DefaultHomePreference()
                SectionLabel(context.getString(TextR.string.start_section_permissions))
            }
            items(items, key = { it.id }) { item ->
                // Read so the row recomposes when [refresh] changes.
                val isAllowed = refresh >= 0 && allowed(item)
                PreferenceRow(
                    context.getString(item.title),
                    context.getString(item.description),
                    Modifier.testTag("start:${item.id}"),
                    trailing = {
                        if (isAllowed)
                            Text(
                                context.getString(TextR.string.start_allowed),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        else
                            OutlinedButton(
                                onClick = {
                                    val permission = item.permission
                                    when {
                                        permission == null -> item.special(model)
                                        // Android stops asking after repeated denials.
                                        item.id in denied ->
                                            model.device.launch(
                                                Intent(
                                                    AndroidSettings
                                                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        else -> {
                                            pending = item.id
                                            request.launch(permission)
                                        }
                                    }
                                }
                            ) {
                                Text(context.getString(TextR.string.start_allow))
                            }
                    },
                )
            }
        }
        Button(
            onClick = { if (firstRun) model.finishStart() else model.back() },
            modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("startDone"),
        ) {
            Text(context.getString(TextR.string.start_done))
        }
    }
}
