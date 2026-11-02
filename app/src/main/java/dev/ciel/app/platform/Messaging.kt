package dev.ciel.app.platform

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import dev.ciel.app.security.KeyVault
import dev.ciel.comms.IncomingMessage
import dev.ciel.comms.MessageDraft
import dev.ciel.comms.MessageSender
import dev.ciel.comms.NotificationSource
import dev.ciel.comms.Platform
import dev.ciel.comms.SendResult
import dev.ciel.core.http.HttpRequest
import dev.ciel.core.http.HttpTransport
import dev.ciel.core.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.resume

/** SMS through Android's SmsManager. Reports "submitted" only after the radio confirms sending. */
class SmsSender(private val context: Context) : MessageSender {
    override val platform = Platform.SMS
    override val canSendDirectly = true
    override fun isAvailable() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override suspend fun send(draft: MessageDraft): SendResult = withContext(Dispatchers.Main) {
        if (!isAvailable()) return@withContext SendResult.Failed("SMS permission not granted or no telephony on this device.")
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(draft.body)
        val action = "dev.ciel.SMS_SENT." + UUID.randomUUID()
        val result = withTimeoutOrNull(45_000) {
            suspendCancellableCoroutine<Int> { cont ->
                var remaining = parts.size
                var worst = Activity.RESULT_OK
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (resultCode != Activity.RESULT_OK) worst = resultCode
                        remaining--
                        if (remaining <= 0) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(worst)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sent = ArrayList(parts.indices.map { idx ->
                    PendingIntent.getBroadcast(context, idx, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                })
                try {
                    sms.sendMultipartTextMessage(draft.address, null, parts, sent, null)
                } catch (e: Exception) {
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(-999)
                }
            }
        }
        when (result) {
            null -> SendResult.Failed("No confirmation from the phone's radio within 45 s — the message may or may not have been sent. Check your messaging app.")
            Activity.RESULT_OK -> SendResult.Submitted("The phone network accepted the SMS (${parts.size} part${if (parts.size > 1) "s" else ""}).")
            SmsManager.RESULT_ERROR_NO_SERVICE -> SendResult.Failed("No mobile service.")
            SmsManager.RESULT_ERROR_RADIO_OFF -> SendResult.Failed("Mobile radio is off (airplane mode?).")
            -999 -> SendResult.Failed("Android refused to send the SMS.")
            else -> SendResult.Failed("Sending failed (code $result).")
        }
    }
}

/**
 * WhatsApp has no public API for sending as a personal account. The supported method is the
 * click-to-chat link, which opens the chat with the text filled in; the user taps send.
 */
class WhatsAppSender(private val context: Context) : MessageSender {
    override val platform = Platform.WHATSAPP
    override val canSendDirectly = false
    private fun pkg(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(context, it) }
    override fun isAvailable() = pkg() != null
    override suspend fun send(draft: MessageDraft): SendResult {
        val p = pkg() ?: return SendResult.Failed("WhatsApp is not installed.")
        val number = draft.address.filter { it.isDigit() }
        if (number.length < 8) return SendResult.Failed("WhatsApp needs a full international number (e.g. +27…).")
        val uri = Uri.parse("https://wa.me/$number?text=" + URLEncoder.encode(draft.body, "UTF-8"))
        return openExternal(context, Intent(Intent.ACTION_VIEW, uri).setPackage(p), "WhatsApp")
    }
}

/** Telegram: the share sheet into the Telegram app; the user picks the chat and taps send. */
class TelegramSender(private val context: Context) : MessageSender {
    override val platform = Platform.TELEGRAM
    override val canSendDirectly = false
    override fun isAvailable() = installed(context, "org.telegram.messenger")
    override suspend fun send(draft: MessageDraft): SendResult {
        if (!isAvailable()) return SendResult.Failed("Telegram is not installed.")
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, draft.body).setPackage("org.telegram.messenger")
        return openExternal(context, intent, "Telegram", "Choose ${draft.recipient.displayName}'s chat in Telegram.")
    }
}

/**
 * Email via the user's mail app (Gmail when installed). Sending silently through the Gmail API
 * requires a Google Cloud OAuth client, which is documented as future work in API_SETUP.md.
 */
class EmailSender(private val context: Context) : MessageSender {
    override val platform = Platform.EMAIL
    override val canSendDirectly = false
    override fun isAvailable() = true
    override suspend fun send(draft: MessageDraft): SendResult {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(draft.address))
            .putExtra(Intent.EXTRA_SUBJECT, draft.subject ?: "")
            .putExtra(Intent.EXTRA_TEXT, draft.body)
        if (installed(context, "com.google.android.gm")) intent.setPackage("com.google.android.gm")
        return openExternal(context, intent, if (intent.`package` != null) "Gmail" else "your email app")
    }
}

/**
 * Discord: messages go to channels through webhooks the user creates (Server Settings →
 * Integrations → Webhooks) — the authorised way to post programmatically. Personal DMs are not
 * possible without violating Discord's terms, so they are not offered.
 */
class DiscordWebhookSender(private val vault: KeyVault, private val http: HttpTransport, private val names: () -> List<String>) : MessageSender {
    override val platform = Platform.DISCORD
    override val canSendDirectly = true
    override fun isAvailable() = names().isNotEmpty()
    override suspend fun send(draft: MessageDraft): SendResult {
        val url = vault.get(KeyVault.discordWebhookName(draft.address)) ?: return SendResult.Failed("No webhook saved for \"${draft.address}\".")
        if (!url.startsWith("https://discord.com/api/webhooks/") && !url.startsWith("https://discordapp.com/api/webhooks/")) return SendResult.Failed("The saved webhook URL is not a Discord webhook.")
        return try {
            val r = http.execute(HttpRequest(url, "POST", mapOf("Content-Type" to "application/json"), json { "content" to draft.body.take(2000) }.toString()))
            if (r.code == 204 || r.isSuccess) SendResult.Submitted("Discord accepted the message for #${draft.address}.")
            else SendResult.Failed("Discord rejected it (HTTP ${r.code}).")
        } catch (e: Exception) {
            SendResult.Failed("Could not reach Discord: ${e.message}")
        }
    }
}

private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

private suspend fun openExternal(context: Context, intent: Intent, app: String, extra: String = "Tap send to deliver it."): SendResult = withContext(Dispatchers.Main) {
    try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        SendResult.HandedToApp(app, extra)
    } catch (e: Exception) {
        SendResult.Failed("Could not open $app: ${e.message}")
    }
}

// ---------------------------------------------------------------------------------------------
// Notification access (user-granted). Messages are kept in memory only, never persisted.
// ---------------------------------------------------------------------------------------------
class CielNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in messagingApps
        if (!isMessage) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        synchronized(buffer) {
            buffer.addLast(IncomingMessage(label, title, text.take(1000), sbn.postTime, group))
            while (buffer.size > 100) buffer.removeFirst()
        }
    }

    companion object {
        @Volatile var connected = false
        private val buffer = ArrayDeque<IncomingMessage>()
        private val messagingApps = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.discord",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.gm", "com.facebook.orca", "com.instagram.android")

        fun source(checker: CapabilityChecker) = object : NotificationSource {
            override val isAuthorised: Boolean get() = checker.notificationAccess()
            override suspend fun recentMessages(limit: Int): List<IncomingMessage> = synchronized(buffer) { buffer.toList().takeLast(limit) }
        }

        fun clear() = synchronized(buffer) { buffer.clear() }
    }
}
