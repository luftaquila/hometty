package io.luftaquila.hometty.platform

import io.luftaquila.hometty.resources.R as TextR
import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.luftaquila.hometty.core.Cli
import io.luftaquila.hometty.core.CliUsageException
import io.luftaquila.hometty.data.Storage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What components Android creates itself (the notification listener, background workers) take
 * from the application, since they cannot receive constructor arguments.
 */
interface PlatformDependencies {
    val storage: Storage
    val notifications: NotificationHub
    val network: NetworkData
}

sealed interface PlatformRequest {
    data class Launch(val intent: Intent) : PlatformRequest

    data class Permission(val names: List<String>) : PlatformRequest

    data object OpenDocument : PlatformRequest
}

class DeviceAccess(private val context: Context) {
    private val requests = Channel<PlatformRequest>(Channel.BUFFERED)
    val events = requests.receiveAsFlow()

    /**
     * Whether the launcher's window is started (set by MainActivity). Only then are screens opened:
     * a request made in the background (a delayed call, a job) would otherwise wait and open, or
     * dial, when the launcher next comes back.
     */
    @Volatile var shown = false

    fun request(request: PlatformRequest) {
        check(shown) { context.getString(TextR.string.message_open_launcher_first) }
        check(requests.trySend(request).isSuccess) { context.getString(TextR.string.message_action_queue_full) }
    }

    fun launch(intent: Intent) = request(PlatformRequest.Launch(intent))

    /** Whether every one of [names] is granted, without asking for any. */
    fun granted(vararg names: String): Boolean =
        names.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun permission(vararg names: String): Boolean {
        val missing =
            names.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) request(PlatformRequest.Permission(missing))
        return missing.isEmpty()
    }

    fun settings(action: String, packageSpecific: Boolean = false) =
        launch(
            Intent(action).apply {
                if (packageSpecific) data = Uri.parse("package:${context.packageName}")
            }
        )

    data class App(
        val id: String,
        val label: String,
        val packageName: String,
        val component: ComponentName,
        val user: UserHandle,
    )

    fun apps(): List<App> {
        val manager = context.getSystemService(LauncherApps::class.java)
        val users = context.getSystemService(UserManager::class.java)
        val profiles =
            manager.profiles.flatMap { user ->
                runCatching {
                        manager.getActivityList(null, user).map { info ->
                            App(
                                info.componentName.flattenToString() +
                                    "@" +
                                    users.getSerialNumberForUser(user),
                                info.label.toString(),
                                info.applicationInfo.packageName,
                                info.componentName,
                                user,
                            )
                        }
                    }
                    .getOrDefault(emptyList())
            }
        // Query the current user's launcher intents independently of LauncherApps.
        // Keep work-profile identities and remove duplicate current-user activities.
        val user = Process.myUserHandle()
        val ownApps =
            context.packageManager
                .queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                )
                .map { info ->
                    val component =
                        ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                    App(
                        component.flattenToString() + "@" + users.getSerialNumberForUser(user),
                        info.loadLabel(context.packageManager).toString(),
                        info.activityInfo.packageName,
                        component,
                        user,
                    )
                }
        return (profiles + ownApps)
            // Names reach the terminal and the shell's PATH: printable, on one line.
            .map { it.copy(label = io.luftaquila.hometty.core.printable(it.label).replace('\n', ' ').trim()) }
            .distinctBy { it.id }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchApp(app: App) {
        context
            .getSystemService(LauncherApps::class.java)
            .startMainActivity(app.component, app.user, null, null)
    }

    /**
     * Opens what a notification of [app] opens when tapped in the shade: its [intent], or its app
     * for [user] when it has none or the app has since cancelled it.
     */
    fun openNotification(intent: PendingIntent?, packageName: String, user: UserHandle, app: String) {
        if (intent != null) {
            // The launcher is in front when its line is tapped, but the app that made the intent
            // usually is not: Android starts the activity only with the launcher's leave.
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 36)
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                )
            else if (Build.VERSION.SDK_INT >= 34)
                @Suppress("DEPRECATION")
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            try {
                intent.send(context, 0, null, null, null, null, options.toBundle())
                return
            } catch (_: PendingIntent.CanceledException) {}
        }
        val launcher = context.getSystemService(LauncherApps::class.java)
        val activity =
            launcher.getActivityList(packageName, user).firstOrNull()
                ?: error(context.getString(TextR.string.message_cannot_open_app, app))
        launcher.startMainActivity(activity.componentName, user, null, null)
    }

    fun open(url: String) {
        val uri = Uri.parse(url)
        require(uri.scheme in setOf("https", "http", "mailto", "tel", "geo")) {
            context.getString(TextR.string.message_unsupported_url_scheme)
        }
        launch(Intent(Intent.ACTION_VIEW, uri))
    }

    fun share(text: String) =
        launch(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                context.getString(TextR.string.label_share),
            )
        )

    fun copy(text: String) {
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("hometty", text))
    }

    fun contacts(query: String): String {
        if (!permission(Manifest.permission.READ_CONTACTS))
            error(context.getString(TextR.string.message_grant_contacts_permission_then_retry))
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        return context.contentResolver
            .query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1).orEmpty()
                            val phone = cursor.getString(2).orEmpty()
                            if (name.contains(query, true) || phone.contains(query))
                                add(Cli.record(name, "phone" to phone, "id" to cursor.getLong(0).toString()))
                            if (size >= 200) break
                        }
                    }
                    .joinToString("\n")
            }
            .orEmpty()
    }

    fun contactEditor(id: String?) {
        launch(
            if (id == null) Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            else
                Intent(
                    Intent.ACTION_EDIT,
                    ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI,
                        id.toLongOrNull() ?: throw CliUsageException("contacts edit: expected a contact ID"),
                    ),
                )
        )
    }

    /** Calls a phone number, or the contact whose name matches [target]. */
    fun call(target: String): String {
        val query = target.trim()
        require(query.isNotEmpty()) {
            context.getString(TextR.string.message_enter_a_phone_number_use_contacts_list_to_find_it)
        }
        val number =
            if (query.matches(Regex("[+0-9*#() -]{1,64}"))) query
            else {
                if (!permission(Manifest.permission.READ_CONTACTS))
                    error(context.getString(TextR.string.message_grant_contacts_permission_then_retry))
                contactNumber(query)
            }
        if (!permission(Manifest.permission.CALL_PHONE))
            error(context.getString(TextR.string.message_grant_phone_permission_then_retry))
        launch(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        return context.getString(TextR.string.message_opening_call)
    }

    /**
     * The number of the one contact named [name]: an exact name match (case and spaces ignored)
     * first, otherwise names containing it. Prefers the default number, then a mobile number.
     */
    private fun contactNumber(name: String): String {
        data class Phone(val id: Long, val name: String, val number: String, val rank: Int)
        fun key(text: String) = text.filterNot(Char::isWhitespace).lowercase()
        val phones =
            context.contentResolver
                .query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                    ),
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val number = cursor.getString(2).orEmpty()
                            if (number.isBlank()) continue
                            val rank =
                                when {
                                    cursor.getInt(3) != 0 -> 0
                                    cursor.getInt(4) ==
                                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> 1
                                    else -> 2
                                }
                            add(Phone(cursor.getLong(0), cursor.getString(1).orEmpty(), number, rank))
                        }
                    }
                }
                .orEmpty()
        val wanted = key(name)
        val matches =
            phones.filter { key(it.name) == wanted }.ifEmpty {
                phones.filter { key(it.name).contains(wanted) }
            }
        val contacts = matches.groupBy { it.id }
        require(contacts.isNotEmpty()) {
            context.getString(TextR.string.message_contact_not_found, name)
        }
        require(contacts.size == 1) {
            context.getString(
                TextR.string.message_contact_ambiguous,
                contacts.values.joinToString(", ") { it.first().name },
            )
        }
        return contacts.values.single().minBy { it.rank }.number
    }

    fun brightness(value: String): String {
        if (!Settings.System.canWrite(context)) {
            settings(Settings.ACTION_MANAGE_WRITE_SETTINGS, true)
            error(context.getString(TextR.string.message_enable_modify_system_settings_then_retry))
        }
        if (value == "auto")
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
            )
        else {
            val percent =
                value.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: throw CliUsageException("brightness: expected 0-100 or auto")
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                ((percent * 255) / 100).coerceAtLeast(1),
            )
        }
        return "Brightness: $value"
    }

    fun volume(value: String?): String {
        val audio = context.getSystemService(AudioManager::class.java)
        if (value == null)
            return "Volume: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        if (value in listOf("normal", "silent", "vibrate")) {
            if (
                !context
                    .getSystemService(NotificationManager::class.java)
                    .isNotificationPolicyAccessGranted
            ) {
                settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                error(context.getString(TextR.string.message_grant_do_not_disturb_access_then_retry))
            }
            audio.ringerMode =
                when (value) {
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    else -> AudioManager.RINGER_MODE_VIBRATE
                }
        } else {
            val percent =
                value.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: throw CliUsageException("volume: expected 0-100, normal, silent or vibrate")
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                percent * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 100,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        return "Volume: $value"
    }

    private var torchOn = false

    fun flash(value: String?): String {
        val camera = context.getSystemService(CameraManager::class.java)
        val id =
            camera.cameraIdList.firstOrNull {
                camera
                    .getCameraCharacteristics(it)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ==
                    true
            } ?: error(context.getString(TextR.string.message_no_flashlight_hardware))
        val next =
            when (value) {
                "on" -> true
                "off" -> false
                null -> !torchOn
                else -> error(context.getString(TextR.string.message_use_on_or_off))
            }
        camera.setTorchMode(id, next)
        torchOn = next
        return "Torch requested: $next"
    }

    fun vibrate(milliseconds: Long) {
        if (milliseconds !in 1..5000) throw CliUsageException("vibrate: expected 1-5000 milliseconds")
        context
            .getSystemService(Vibrator::class.java)
            .vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun status(): Map<String, String> {
        val battery =
            context
                .getSystemService(BatteryManager::class.java)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val memory =
            ActivityManager.MemoryInfo().also {
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
            }
        val conn = context.getSystemService(ConnectivityManager::class.java)
        val network = conn.getNetworkCapabilities(conn.activeNetwork)
        val networkText =
            when {
                network == null -> "offline"
                network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "connected"
            }
        val storage = StatFs(context.filesDir.path)
        fun gib(bytes: Long) = "%.1f GB".format(bytes / 1073741824.0)
        return mapOf(
            "battery" to (if (battery in 0..100) "$battery%" else "unknown"),
            "memory" to "${gib(memory.availMem)}/${gib(memory.totalMem)}",
            "storage" to "${gib(storage.availableBytes)}/${gib(storage.totalBytes)}",
            "network" to networkText,
            "device" to Build.MODEL,
        )
    }

    /** Whether the battery is charging. */
    fun charging(): Boolean = context.getSystemService(BatteryManager::class.java).isCharging

    suspend fun location(): String =
        coordinates().let {
            "latitude: ${it.latitude}\nlongitude: ${it.longitude}\naccuracy: ±${it.accuracy.toInt()} m"
        }

    /**
     * Where the device is, from the first update of a new request, waiting up to 5 seconds; with
     * none by then, the latest fix any provider has. With approximate location Android answers the
     * request with its latest fix and makes a new one at most every 10 minutes. [ask] false only
     * checks the permission, for work the user did not start, so no permission dialog appears.
     */
    suspend fun coordinates(ask: Boolean = true): android.location.Location {
        val allowed =
            if (ask) permission(Manifest.permission.ACCESS_COARSE_LOCATION)
            else granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!allowed) error(context.getString(TextR.string.message_grant_location_permission_then_retry))
        val manager = context.getSystemService(LocationManager::class.java)
        // Not the passive provider: it never makes a fix of its own.
        val enabled = manager.getProviders(true)
        val provider =
            // "fused": LocationManager.FUSED_PROVIDER, named only from API 31.
            listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
                .firstOrNull { it in enabled }
                ?: error(context.getString(TextR.string.message_location_is_disabled))
        // withTimeoutOrNull: a timeout must fail with a message, not end the command silently.
        val location =
            kotlinx.coroutines.withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<android.location.Location> { continuation ->
                    val listener =
                        object : android.location.LocationListener {
                            override fun onLocationChanged(location: android.location.Location) {
                                manager.removeUpdates(this)
                                if (continuation.isActive) continuation.resume(location)
                            }

                            override fun onProviderDisabled(provider: String) {
                                manager.removeUpdates(this)
                                if (continuation.isActive)
                                    continuation.resumeWithException(
                                        IllegalStateException(context.getString(TextR.string.message_location_is_disabled))
                                    )
                            }
                        }
                    try {
                        manager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper(),
                        )
                    } catch (error: SecurityException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                }
            } ?: latestKnown(manager) ?: error(context.getString(TextR.string.message_location_timeout))
        return location
    }

    /** The newest fix any provider kept, however old; null when none has one. */
    private fun latestKnown(manager: LocationManager): android.location.Location? =
        manager
            .getProviders(false)
            .mapNotNull {
                try {
                    manager.getLastKnownLocation(it)
                } catch (_: SecurityException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .maxByOrNull { it.elapsedRealtimeNanos }

    /**
     * The city and district at [location], in the language Android's geocoder gives;
     * null when it cannot tell within 5 seconds. Its nearest addresses do not all have every part,
     * so each part comes from the first that has it.
     */
    suspend fun place(location: android.location.Location): Place? {
        if (!android.location.Geocoder.isPresent()) return null
        val geocoder = android.location.Geocoder(context)
        val addresses =
            kotlinx.coroutines.withTimeoutOrNull(5000) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            5,
                            object : android.location.Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                    if (continuation.isActive) continuation.resume(addresses.toList())
                                }

                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(emptyList())
                                }
                            },
                        )
                    }
                else
                    // A blocking call cannot be cancelled: run it apart and stop waiting at the
                    // timeout instead (withContext would wait for it).
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                        .async {
                            try {
                                @Suppress("DEPRECATION")
                                geocoder.getFromLocation(location.latitude, location.longitude, 5).orEmpty()
                            } catch (_: java.io.IOException) {
                                emptyList()
                            }
                        }
                        .await()
            }
        if (addresses.isNullOrEmpty()) return null
        fun first(part: (android.location.Address) -> String?) =
            addresses.firstNotNullOfOrNull { part(it)?.takeIf(String::isNotBlank) }.orEmpty()
        return Place(
            // A metropolitan city such as Daejeon or Seoul has no locality; it is the province-level area.
            city = first { it.locality }.ifEmpty { first { it.adminArea } },
            district = first { it.subLocality },
        )
    }
}

/** Names for where the device is; a part Android does not know is empty. */
data class Place(val city: String, val district: String)
