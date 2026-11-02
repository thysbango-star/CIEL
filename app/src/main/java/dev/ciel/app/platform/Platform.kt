package dev.ciel.app.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.ciel.app.CielApplication
import dev.ciel.app.R
import dev.ciel.app.data.ReminderDao
import dev.ciel.app.data.ReminderEntity
import dev.ciel.comms.Contact
import dev.ciel.comms.ContactDirectory
import dev.ciel.game.InstalledPackageInfo
import dev.ciel.skills.Capability
import dev.ciel.skills.builtin.DocumentStore
import dev.ciel.skills.builtin.Reminder
import dev.ciel.skills.builtin.ReminderScheduler
import dev.ciel.task.ActionOutcome
import dev.ciel.task.DeviceAction
import dev.ciel.task.DeviceController
import dev.ciel.task.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Device control: intents for launching things, the accessibility service for everything on screen. */
class AndroidDeviceController(private val context: Context) : DeviceController {
    override val isAvailable: Boolean get() = CielAccessibilityService.instance != null

    override suspend fun observe(includeScreenshot: Boolean): ScreenSnapshot =
        CielAccessibilityService.instance?.observe(includeScreenshot)
            ?: throw IllegalStateException("Ciel's accessibility service is not enabled.")

    override suspend fun perform(action: DeviceAction): ActionOutcome = withContext(Dispatchers.Main) {
        when (action) {
            is DeviceAction.OpenApp -> {
                val intent = context.packageManager.getLaunchIntentForPackage(action.packageName)
                    ?: return@withContext ActionOutcome.Failed("${action.packageName} is not installed or has no launcher screen.")
                startIntent(intent)
            }
            is DeviceAction.OpenUrl -> startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
            is DeviceAction.OpenSettings -> startIntent(Intent(settingsAction(action.screen)))
            else -> CielAccessibilityService.instance?.perform(action)
                ?: ActionOutcome.Unsupported("Screen control needs Ciel's accessibility service (Settings → Accessibility → Ciel).")
        }
    }

    private fun startIntent(intent: Intent): ActionOutcome = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The accessibility service context is exempt from background-launch limits; fall back to app context.
        (CielAccessibilityService.instance ?: context).startActivity(intent)
        ActionOutcome.Done
    } catch (e: Exception) {
        ActionOutcome.Failed("Android refused to open it: ${e.message}")
    }

    private fun settingsAction(screen: String): String = when (screen.lowercase()) {
        "wifi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "display" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound" -> Settings.ACTION_SOUND_SETTINGS
        "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
        "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "security" -> Settings.ACTION_SECURITY_SETTINGS
        "assistant" -> Settings.ACTION_VOICE_INPUT_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }

    override suspend fun installedApps(): Map<String, String> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).associate { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }
}

class AndroidInstalledPackages(private val context: Context) : InstalledPackageInfo {
    private fun info(pkg: String): ApplicationInfo? = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
    override fun isGame(packageName: String): Boolean = info(packageName)?.category == ApplicationInfo.CATEGORY_GAME
    override fun versionName(packageName: String): String? = runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
    override fun label(packageName: String): String? = info(packageName)?.let { context.packageManager.getApplicationLabel(it).toString() }
}

/** Live connectivity, so Ciel switches between online and offline behaviour automatically. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(check())
    val online: StateFlow<Boolean> = _online

    init {
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = check() }
                override fun onLost(network: Network) { _online.value = check() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _online.value = check() }
            })
    }

    private fun check(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** Which capabilities are actually granted right now. */
class CapabilityChecker(private val context: Context) {
    fun granted(): Set<Capability> = buildSet {
        add(Capability.INTERNET)
        if (CielAccessibilityService.instance != null) { add(Capability.ACCESSIBILITY); add(Capability.SCREENSHOT) }
        if (Settings.canDrawOverlays(context)) add(Capability.OVERLAY)
        if (has(Manifest.permission.RECORD_AUDIO)) add(Capability.MICROPHONE)
        if (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)) add(Capability.NOTIFICATIONS_POST)
        if (notificationAccess()) add(Capability.NOTIFICATION_ACCESS)
        if (has(Manifest.permission.READ_CONTACTS)) add(Capability.CONTACTS)
        if (has(Manifest.permission.SEND_SMS)) add(Capability.SMS)
        if (Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add(Capability.EXACT_ALARMS)
    }

    fun has(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(context, CielNotificationListener::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }

    fun accessibilityEnabled() = CielAccessibilityService.instance != null
}

// ---------------------------------------------------------------------------------------------
// Reminders: stored in Room, fired by AlarmManager, re-armed after reboot.
// ---------------------------------------------------------------------------------------------
class AndroidReminderScheduler(private val context: Context, private val dao: ReminderDao) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override suspend fun schedule(text: String, triggerAtMillis: Long): Reminder {
        val r = Reminder(UUID.randomUUID().toString().take(8), text, triggerAtMillis)
        dao.insert(ReminderEntity(r.id, r.text, r.triggerAtMillis))
        arm(r)
        return r
    }

    fun arm(r: Reminder) {
        val pi = pending(r.id, r.text)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMillis, pi)
        } else {
            // Without the exact-alarm permission Android may deliver it a few minutes late.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMillis, pi)
        }
    }

    override suspend fun list(): List<Reminder> {
        dao.deleteBefore(System.currentTimeMillis() - 60_000)
        return dao.all().map { Reminder(it.id, it.text, it.triggerAt) }
    }

    override suspend fun cancel(id: String): Boolean {
        alarms.cancel(pending(id, ""))
        return dao.delete(id) > 0
    }

    suspend fun rearmAll() = list().forEach { arm(it) }

    private fun pending(id: String, text: String): PendingIntent = PendingIntent.getBroadcast(
        context, id.hashCode(),
        Intent(context, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val id = intent.getStringExtra("id") ?: "r"
        Notifications.ensureChannels(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, dev.ciel.app.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notifications.REMINDERS)
            .setSmallIcon(R.drawable.ic_ciel_mono)
            .setContentTitle("Notice. Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n) }
        val pending = goAsync()
        CielApplication.graph.appScope.launch {
            try { CielApplication.graph.db.reminders().delete(id) } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CielApplication.graph.appScope.launch {
            try { CielApplication.graph.reminders.rearmAll() } finally { pending.finish() }
        }
    }
}

object Notifications {
    const val REMINDERS = "reminders"
    const val SERVICES = "services"
    const val TASKS = "tasks"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(SERVICES, "Listening & floating Ciel", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Ciel is listening for her name or floating above other apps."
        })
        nm.createNotificationChannel(NotificationChannel(TASKS, "Task reports", NotificationManager.IMPORTANCE_DEFAULT))
    }
}

// ---------------------------------------------------------------------------------------------
// Documents: Documents/Ciel via MediaStore (no storage permission needed on Android 10+).
// ---------------------------------------------------------------------------------------------
class MediaStoreDocumentStore(private val context: Context) : DocumentStore {
    override suspend fun save(fileName: String, mimeType: String, content: String): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Ciel")
            }
            val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: throw IllegalStateException("Android did not create the file.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: throw IllegalStateException("Could not open the file for writing.")
            // Verify by reading back.
            val back = context.contentResolver.openInputStream(uri)?.use { it.readBytes().size } ?: -1
            if (back != content.toByteArray().size) throw IllegalStateException("The saved file could not be verified.")
            "Documents/Ciel/$fileName"
        } else {
            val dir = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "Ciel").apply { mkdirs() }
            val f = java.io.File(dir, fileName)
            f.writeText(content)
            if (f.length() != content.toByteArray().size.toLong()) throw IllegalStateException("The saved file could not be verified.")
            f.absolutePath
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Contacts (READ_CONTACTS) + configured Discord webhooks.
// ---------------------------------------------------------------------------------------------
class AndroidContactDirectory(private val context: Context, private val discordWebhooks: () -> List<String>) : ContactDirectory {
    override suspend fun find(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, Contact>()
        discordWebhooks().filter { it.contains(query, ignoreCase = true) }.forEach {
            result["discord:$it"] = Contact("discord:$it", it, discordWebhookName = it, isGroup = true)
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return@withContext result.values.toList()
        val cr = context.contentResolver
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val num = c.getString(2) ?: continue
                val prev = result[id]
                result[id] = (prev ?: Contact(id, name)).let { it.copy(phoneNumbers = (it.phoneNumbers + num.filter { ch -> ch.isDigit() || ch == '+' }).distinct()) }
            }
        }
        cr.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$query%"), null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mail = c.getString(2) ?: continue
                val prev = result[id]
                result[id] = (prev ?: Contact(id, name)).let { it.copy(emails = (it.emails + mail).distinct()) }
            }
        }
        result.values.toList()
    }
}
