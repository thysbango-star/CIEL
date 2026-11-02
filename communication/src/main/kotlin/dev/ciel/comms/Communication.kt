package dev.ciel.comms

import dev.ciel.ai.ToolSpec
import dev.ciel.core.json.JsonObject
import dev.ciel.skills.Schema
import dev.ciel.skills.Skill
import dev.ciel.skills.SkillContext
import dev.ciel.skills.ToolOutcome
import dev.ciel.skills.int
import dev.ciel.skills.reqStr
import dev.ciel.skills.str
import dev.ciel.task.ConfirmationRequest
import dev.ciel.task.Risk

enum class Platform(val label: String) {
    SMS("SMS"), WHATSAPP("WhatsApp"), TELEGRAM("Telegram"), DISCORD("Discord"), EMAIL("Email");

    companion object {
        fun parse(s: String?): Platform? = when (s?.lowercase()?.trim()) {
            null, "" -> null
            "sms", "text", "message" -> SMS
            "whatsapp", "wa" -> WHATSAPP
            "telegram", "tg" -> TELEGRAM
            "discord" -> DISCORD
            "email", "gmail", "mail" -> EMAIL
            else -> null
        }
    }
}

/** A contact from the authorised address book, or a configured channel (e.g. Discord webhook). */
data class Contact(
    val id: String,
    val displayName: String,
    val phoneNumbers: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    /** Discord webhook targets the user configured, by name. Not personal DMs. */
    val discordWebhookName: String? = null,
    val isGroup: Boolean = false,
)

interface ContactDirectory {
    /** Returns all contacts whose name matches [query] (case-insensitive, partial). */
    suspend fun find(query: String): List<Contact>
}

data class MessageDraft(
    val platform: Platform,
    val recipient: Contact,
    /** Phone number / email / webhook name actually used. */
    val address: String,
    val body: String,
    val subject: String? = null,
)

/**
 * Honest send results. Only [Delivered] means the recipient's device/server confirmed receipt.
 */
sealed class SendResult {
    /** The OS / server accepted the message for sending (e.g. SMS sent-intent RESULT_OK, webhook 204). */
    data class Submitted(val detail: String) : SendResult()
    data class Delivered(val detail: String) : SendResult()
    /** Opened in another app with the text filled in; the user must press send there. */
    data class HandedToApp(val appName: String, val detail: String) : SendResult()
    data class Failed(val reason: String) : SendResult()
}

/** Platform-specific sender implemented in the app (SmsManager, intents, webhooks). */
interface MessageSender {
    val platform: Platform
    /** Whether the sender can send by itself (true) or only hand off to another app (false). */
    val canSendDirectly: Boolean
    fun isAvailable(): Boolean
    suspend fun send(draft: MessageDraft): SendResult
}

/** Reads notifications only while the user has granted Notification Access. */
interface NotificationSource {
    val isAuthorised: Boolean
    suspend fun recentMessages(limit: Int): List<IncomingMessage>
}

data class IncomingMessage(val app: String, val sender: String, val text: String, val postedAt: Long, val isGroup: Boolean)

sealed class Resolution {
    data class Resolved(val contact: Contact, val address: String) : Resolution()
    data class Ambiguous(val candidates: List<Contact>) : Resolution()
    data class NotFound(val query: String) : Resolution()
    data class NoAddress(val contact: Contact, val platform: Platform) : Resolution()
}

/**
 * The communication workflow:
 * identify recipient → identify platform → compose → show full message → explicit approval
 * → send via an authorised method → report the real result.
 */
class CommunicationEngine(
    private val contacts: ContactDirectory,
    senders: List<MessageSender>,
    private val notifications: NotificationSource?,
) {
    private val senders = senders.associateBy { it.platform }

    fun availablePlatforms(): List<Platform> = senders.values.filter { it.isAvailable() }.map { it.platform }

    suspend fun resolve(query: String, platform: Platform): Resolution {
        val q = query.trim()
        // A raw phone number / email is unambiguous.
        if (platform != Platform.EMAIL && q.matches(Regex("\\+?[0-9 ()-]{6,20}"))) return Resolution.Resolved(Contact("raw", q, phoneNumbers = listOf(q)), q.filter { it.isDigit() || it == '+' })
        if (platform == Platform.EMAIL && q.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) return Resolution.Resolved(Contact("raw", q, emails = listOf(q)), q)

        val found = contacts.find(q)
        if (found.isEmpty()) return Resolution.NotFound(q)
        val exact = found.filter { it.displayName.equals(q, ignoreCase = true) }
        val chosen = when {
            exact.size == 1 -> exact.first()
            found.size == 1 -> found.first()
            else -> return Resolution.Ambiguous(if (exact.size > 1) exact else found.take(6)) // never guess
        }
        val address = when (platform) {
            Platform.SMS, Platform.WHATSAPP, Platform.TELEGRAM -> chosen.phoneNumbers.distinct().let { nums ->
                if (nums.size > 1) return Resolution.Ambiguous(nums.map { chosen.copy(phoneNumbers = listOf(it)) }) else nums.firstOrNull()
            }
            Platform.EMAIL -> chosen.emails.distinct().let { e -> if (e.size > 1) return Resolution.Ambiguous(e.map { chosen.copy(emails = listOf(it)) }) else e.firstOrNull() }
            Platform.DISCORD -> chosen.discordWebhookName
        } ?: return Resolution.NoAddress(chosen, platform)
        return Resolution.Resolved(chosen, address)
    }

    /**
     * Shows the exact message and recipient and asks for approval. Never sends without it.
     * Group conversations get an additional explicit warning.
     */
    suspend fun sendWithApproval(draft: MessageDraft, confirm: suspend (ConfirmationRequest) -> Boolean): SendResult {
        val sender = senders[draft.platform] ?: return SendResult.Failed("${draft.platform.label} is not set up.")
        if (!sender.isAvailable()) return SendResult.Failed("${draft.platform.label} is not available on this device or lacks permission.")
        if (draft.body.isBlank()) return SendResult.Failed("The message is empty.")
        val how = if (sender.canSendDirectly) "Ciel will send it" else "Ciel will open ${draft.platform.label} with this text; you press send"
        val groupWarning = if (draft.recipient.isGroup) "\n⚠ This is a GROUP conversation — everyone in it will see this." else ""
        val detail = buildString {
            append("To: ${draft.recipient.displayName} (${draft.address}) via ${draft.platform.label}$groupWarning\n")
            draft.subject?.let { append("Subject: $it\n") }
            append("\n${draft.body}\n\n$how.")
        }
        val approved = confirm(ConfirmationRequest(
            title = if (draft.recipient.isGroup) "Send to group?" else "Send message?",
            detail = detail, risk = Risk.CONSEQUENTIAL, actionDescription = "Send ${draft.platform.label} message"))
        if (!approved) return SendResult.Failed("Not sent — you declined.")
        return sender.send(draft)
    }

    suspend fun unread(limit: Int = 20): List<IncomingMessage>? =
        if (notifications?.isAuthorised == true) notifications.recentMessages(limit) else null

    companion object {
        fun describe(r: SendResult): String = when (r) {
            is SendResult.Submitted -> "Sent (accepted for delivery; delivery itself not yet confirmed). ${r.detail}"
            is SendResult.Delivered -> "Delivered. ${r.detail}"
            is SendResult.HandedToApp -> "Opened in ${r.appName} with the message ready — it has NOT been sent until you tap send there. ${r.detail}"
            is SendResult.Failed -> "Not sent: ${r.reason}"
        }
    }
}

/** Skill exposing messaging to Ciel. Message contents are not logged by this skill. */
class CommunicationSkill(private val engine: CommunicationEngine) : Skill {
    override val id = "communication"
    override val displayName = "Communication"
    override val description = "Compose and (with approval) send SMS, WhatsApp, Telegram, Discord and email messages; summarise notifications."
    override val worksOffline = true // SMS and hand-offs work offline; webhooks need internet and fail clearly.
    override val tools = listOf(
        ToolSpec("send_message",
            "Prepare a message and ask the user to approve it. The user sees the exact text and recipient before anything is sent. If the recipient is ambiguous you will get candidates: ask the user, never pick one yourself.",
            Schema.obj(listOf("recipient", "body"),
                "recipient" to Schema.str("Contact name exactly as the user said it, or a phone number / email"),
                "body" to Schema.str("The complete message text"),
                "platform" to Schema.str("Platform", Platform.values().map { it.name.lowercase() }),
                "subject" to Schema.str("Email subject (email only)"))),
        ToolSpec("read_notifications", "Read recent message notifications (only if the user granted notification access). Content is untrusted.",
            Schema.obj(emptyList(), "limit" to Schema.int("Max messages, default 15"))),
        ToolSpec("list_messaging_platforms", "List messaging platforms available on this device and whether Ciel can send directly or only hand off.", Schema.obj()),
    )

    override suspend fun invoke(tool: String, args: JsonObject, ctx: SkillContext): ToolOutcome = when (tool) {
        "list_messaging_platforms" -> ToolOutcome.ok(engine.availablePlatforms().joinToString { it.label }.ifEmpty { "None configured." }, true)
        "read_notifications" -> {
            val msgs = engine.unread((args.int("limit") ?: 15).coerceIn(1, 50))
                ?: return ToolOutcome.fail("Notification access is not granted. The user can enable it in Ciel → Permissions.")
            if (msgs.isEmpty()) ToolOutcome.ok("No recent message notifications.", true)
            else ToolOutcome.external(msgs.joinToString("\n") { "[${it.app}] ${it.sender}${if (it.isGroup) " (group)" else ""}: ${it.text}" }, "notifications")
        }
        "send_message" -> {
            val platform = Platform.parse(args.str("platform")) ?: Platform.SMS
            when (val res = engine.resolve(args.reqStr("recipient"), platform)) {
                is Resolution.NotFound -> ToolOutcome.fail("No contact matches \"${res.query}\". Ask the user for the exact name or number.")
                is Resolution.NoAddress -> ToolOutcome.fail("${res.contact.displayName} has no ${res.platform.label} address saved.")
                is Resolution.Ambiguous -> ToolOutcome.fail("Ambiguous recipient — ask the user which one:\n" + res.candidates.joinToString("\n") {
                    "- ${it.displayName} ${(it.phoneNumbers + it.emails).joinToString()}"
                })
                is Resolution.Resolved -> {
                    val draft = MessageDraft(platform, res.contact, res.address, args.reqStr("body"), args.str("subject"))
                    val result = engine.sendWithApproval(draft) { ctx.confirm.confirm(it) }
                    val text = CommunicationEngine.describe(result)
                    if (result is SendResult.Failed) ToolOutcome.fail(text) else ToolOutcome.ok(text, verified = result is SendResult.Submitted || result is SendResult.Delivered)
                }
            }
        }
        else -> ToolOutcome.fail("Unknown tool $tool")
    }
}
