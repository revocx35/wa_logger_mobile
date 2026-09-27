package io.github.revocx35.walmobile.core.wa

import io.github.revocx35.walmobile.core.CallLogInfo
import io.github.revocx35.walmobile.core.ChatKind
import io.github.revocx35.walmobile.core.LocationInfo
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.core.PollInfo
import io.github.revocx35.walmobile.core.PollOption
import io.github.revocx35.walmobile.core.VcardInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

/*
 * Pure mapping from WhatsApp Web's serialized message model (what the page bridge sends, the same object
 * whatsapp-web.js calls `message._data`) to a storage record. Port of the server's `wa/mapper.ts`; the
 * tests are ported too. No I/O, no crypto. WhatsApp's models are loosely typed, so everything is read
 * defensively with JavaScript-like truthiness.
 */

/* ------------------------------------------------------------ JSON helpers */

fun JsonElement?.truthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        booleanOrNull != null -> booleanOrNull!!
        else -> doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: false
    }
    else -> true
}

/** A non-empty string, else null (like the server's `str`). */
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** A finite number (numeric strings accepted, like JS `Number(v)`), else null. */
fun JsonElement?.num(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val d = if (p.isString) {
        val t = p.content.trim()
        if (t.isEmpty()) 0.0 else t.toDoubleOrNull()
    } else {
        if (p.booleanOrNull != null) null else p.doubleOrNull
    }
    return d?.takeIf { it.isFinite() }
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

/** JS `String(v)` for the values WhatsApp puts in lists. */
private fun JsonElement.jsString(): String = when (this) {
    is JsonPrimitive -> if (this is JsonNull) "null" else content
    else -> toString()
}

/** `"123@c.us"` or `{ _serialized: "123@c.us" }` → the id string. */
fun jid(v: JsonElement?): String? {
    if (!v.truthy()) return null
    if (v is JsonPrimitive) return if (v.isString) v.content else null
    return (v as? JsonObject)?.get("_serialized").let { s -> (s as? JsonPrimitive)?.takeIf { it.isString }?.content }
}

/**
 * Serialized WhatsApp message key `<fromMe>_<remote>_<id>[_<participant>][_<self>]` (own messages end in
 * "_out"). Current WhatsApp Web builds no longer expose `_serialized`, so it is rebuilt from the parts
 * (identical to WhatsApp's own MsgKey.toString()).
 */
fun msgKey(v: JsonElement?): String? {
    if (!v.truthy()) return null
    if (v is JsonPrimitive) return if (v.isString) v.content else null
    val o = v as? JsonObject ?: return null
    o["_serialized"].str()?.let { return it }
    val remote = jid(o["remote"]) ?: return null
    val id = o["id"].str() ?: return null
    val participant = jid(o["participant"])
    val self = o["self"].str()?.let { "_$it" } ?: ""
    return "${if (o["fromMe"].truthy()) "true" else "false"}_${remote}_$id${participant?.let { "_$it" } ?: ""}$self"
}

fun chatKind(chatId: String): ChatKind = when {
    chatId == "status@broadcast" -> ChatKind.Status
    chatId.endsWith("@g.us") -> ChatKind.Group
    chatId.endsWith("@newsletter") -> ChatKind.Newsletter
    chatId.endsWith("@broadcast") -> ChatKind.Broadcast
    else -> ChatKind.User
}

private val PHONE_JID = Regex("^(\\d{5,20})@(c\\.us|s\\.whatsapp\\.net)$")

/** Phone number of a phone-number JID (not of `@lid` ids). */
fun phoneOf(id: String?): String? = id?.let { PHONE_JID.matchEntire(it) }?.let { "+${it.groupValues[1]}" }

private val B64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

fun looksLikeBase64Blob(s: String?): Boolean = s != null && s.length >= 200 && B64.matches(s.take(4096))

/* ---------------------------------------------------------------- records */

@Serializable
data class SystemInfo(
    val subtype: String? = null,
    val author: String? = null,
    val recipients: List<String> = emptyList(),
    /** Extra text carried by the notification (e.g. the new group subject). */
    val text: String? = null,
    val templateParams: List<String>? = null,
)

@Serializable
data class QuotedInfo(val stanzaId: String? = null, val participant: String? = null, val type: MessageType = MessageType.Unknown, val text: String? = null)

@Serializable
data class InviteInfo(val groupName: String? = null)

@Serializable
data class MessageMeta(
    val quoted: QuotedInfo? = null,
    val location: LocationInfo? = null,
    val vcards: List<VcardInfo>? = null,
    val poll: PollInfo? = null,
    val call: CallLogInfo? = null,
    val mentions: List<String>? = null,
    val system: SystemInfo? = null,
    val rawType: String? = null,
    val invite: InviteInfo? = null,
    val forwardingScore: Double? = null,
) {
    fun isEmpty() = this == MessageMeta()
}

data class MediaFacts(
    val mime: String? = null,
    val filename: String? = null,
    val size: Long? = null,
    val width: Double? = null,
    val height: Double? = null,
    val durationSec: Double? = null,
)

data class MappedMessage(
    val id: String,
    val chatId: String,
    val senderId: String?,
    val fromMe: Boolean,
    /** epoch ms */
    val ts: Long,
    val type: MessageType,
    val rawType: String,
    /** Text, caption, or null. Never a thumbnail. */
    val body: String?,
    val meta: MessageMeta?,
    /** Base64 JPEG thumbnail WhatsApp provided inline (if any). */
    val thumbBase64: String?,
    val quotedStanzaId: String?,
    val media: MediaFacts?,
    /** WhatsApp holds downloadable media for this message (and it is not view-once). */
    val downloadable: Boolean,
    val isViewOnce: Boolean,
    val isForwarded: Boolean,
    val isStatus: Boolean,
    val ack: Int?,
    val revoked: Boolean,
    val revokedBy: String?,
    val editKey: String?,
    val editTs: Long?,
    val notifyName: String?,
    /** Protocol/noise messages that are not worth storing. */
    val skip: Boolean,
)

private val MEDIA_TYPES = setOf("image", "video", "audio", "ptt", "document", "sticker", "ptv")
private val SKIP_TYPES = setOf("protocol", "debug", "e2e_notification", "reaction", "album", "pin_message", "keep_in_chat", "poll_update", "event_response")
private val SYSTEM_TYPES = setOf("gp2", "notification_template", "broadcast_notification", "group_notification", "notification", "call_log_notification")
private const val MAX_THUMB_B64 = 96 * 1024
private const val MAX_VCARD_CHARS = 32 * 1024
private const val MAX_VCARDS = 30
private val VIEW_ONCE = Regex("view_once", RegexOption.IGNORE_CASE)

private fun mapType(raw: JsonObject, rawType: String): MessageType = when (rawType) {
    "chat" -> MessageType.Chat
    "image" -> MessageType.Image
    "video", "ptv" -> if (raw["isGif"].truthy()) MessageType.Gif else MessageType.Video
    "audio" -> MessageType.Audio
    "ptt" -> MessageType.Ptt
    "document" -> MessageType.Document
    "sticker" -> MessageType.Sticker
    "location" -> if (raw["isLive"].truthy()) MessageType.LiveLocation else MessageType.Location
    "vcard" -> MessageType.Vcard
    "multi_vcard" -> MessageType.MultiVcard
    "poll_creation" -> MessageType.Poll
    "call_log" -> MessageType.CallLog
    "revoked" -> MessageType.Revoked
    "ciphertext" -> MessageType.Ciphertext
    else -> if (rawType in SYSTEM_TYPES) MessageType.System else MessageType.Unknown
}

/** Linear-time FN: extraction (a regex spanning lines is a ReDoS vector for crafted vCards). */
private fun vcardName(vcard: String): String? {
    for (line in vcard.take(MAX_VCARD_CHARS).split(Regex("\r?\n"), 501).take(500)) {
        if (line.length < 3 || (line[0] != 'F' && line[0] != 'f') || (line[1] != 'N' && line[1] != 'n')) continue
        if (line[2] != ':' && line[2] != ';') continue
        val colon = line.indexOf(':')
        if (colon < 0) continue
        val name = line.substring(colon + 1).trim()
        if (name.isNotEmpty()) return name.take(200)
    }
    return null
}

private fun quotedText(q: JsonObject): String? {
    val t = q["type"].str() ?: ""
    if (t in MEDIA_TYPES) return q["caption"].str()
    if (t == "poll_creation") return q["pollName"].str()
    if (t == "location") return "Location"
    if (t == "vcard" || t == "multi_vcard") return "Contact"
    val b = q["body"].str()
    return if (b != null && !looksLikeBase64Blob(b)) b else null
}

private fun rawTypeOf(o: JsonObject): String = when (val t = o["type"]) {
    null, JsonNull -> "unknown"
    else -> t.jsString()
}

fun mapMessage(raw: JsonObject): MappedMessage {
    val idObj = raw["id"].obj() ?: JsonObject(emptyMap())
    val id = msgKey(raw["id"]) ?: ""
    val rawType = rawTypeOf(raw)
    val fromMe = idObj["fromMe"].truthy()
    val chatId = jid(idObj["remote"]) ?: (if (fromMe) jid(raw["to"]) else jid(raw["from"])) ?: "unknown@c.us"
    val isGroupish = chatId.endsWith("@g.us") || chatId == "status@broadcast" || chatId.endsWith("@broadcast")
    val senderId = when {
        fromMe -> null
        isGroupish -> jid(raw["author"]) ?: jid(idObj["participant"]) ?: jid(raw["from"])
        else -> jid(raw["from"]) ?: chatId
    }
    val type = mapType(raw, rawType)
    val isViewOnce = (raw["isViewOnce"] as? JsonPrimitive)?.booleanOrNull == true || VIEW_ONCE.containsMatchIn(rawType)
    val hasDirectPath = raw["directPath"].str() != null
    val isMedia = rawType in MEDIA_TYPES

    var body: String? = null
    var thumb: String? = null
    when {
        isMedia -> {
            body = raw["caption"].str()
            val b = raw["body"].str()
            if (b != null && looksLikeBase64Blob(b) && b.length <= MAX_THUMB_B64 && !isViewOnce) thumb = b
        }
        rawType == "poll_creation" -> body = raw["pollName"].str()
        rawType == "vcard" || rawType == "multi_vcard" || rawType == "location" -> body = null
        rawType in SYSTEM_TYPES || rawType == "revoked" || rawType == "ciphertext" || rawType == "call_log" -> body = null
        else -> body = raw["body"].str()?.takeIf { !looksLikeBase64Blob(it) }
    }

    var quoted: QuotedInfo? = null
    raw["quotedMsg"].obj()?.let { q ->
        quoted = QuotedInfo(raw["quotedStanzaID"].str(), jid(raw["quotedParticipant"]), mapType(q, rawTypeOf(q)), quotedText(q))
    }
    var location: LocationInfo? = null
    if (rawType == "location") {
        val lat = raw["lat"].num()
        val lng = raw["lng"].num()
        if (lat != null && lng != null) {
            val parts = (raw["loc"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.split("\n")
            location = LocationInfo(lat, lng, parts?.getOrNull(0)?.ifEmpty { null }, parts?.getOrNull(1)?.ifEmpty { null }, raw["clientUrl"].str())
        }
    }
    var vcards: List<VcardInfo>? = null
    if (rawType == "vcard") {
        val v = (raw["body"].str() ?: "").take(MAX_VCARD_CHARS)
        vcards = listOf(VcardInfo(raw["vcardFormattedName"].str()?.take(200) ?: vcardName(v), v))
    }
    if (rawType == "multi_vcard") {
        raw["vcardList"].arr()?.let { list ->
            vcards = list.take(MAX_VCARDS).map { c ->
                val co = c.obj()
                val v = (co?.get("vcard")?.let { if (it is JsonNull) "" else it.jsString() } ?: "").take(MAX_VCARD_CHARS)
                VcardInfo(co?.get("displayName").str()?.take(200) ?: vcardName(v), v)
            }
        }
    }
    var poll: PollInfo? = null
    if (rawType == "poll_creation") {
        val opts = raw["pollOptions"].arr() ?: JsonArray(emptyList())
        poll = PollInfo(
            raw["pollName"].str() ?: "",
            opts.map { o -> PollOption(o.obj()?.get("name")?.let { if (it is JsonNull) "" else it.jsString() } ?: "", null) },
            raw["pollSelectableOptionsCount"].num() == 0.0,
        )
    }
    var call: CallLogInfo? = null
    if (rawType == "call_log") call = CallLogInfo(raw["isVideoCall"].truthy(), raw["callOutcome"].str(), raw["callDuration"].num())
    val mentions = raw["mentionedJidList"].arr()?.takeIf { it.isNotEmpty() }?.mapNotNull { jid(it) }
    var system: SystemInfo? = null
    if (rawType in SYSTEM_TYPES) {
        val b = raw["body"].str()
        system = SystemInfo(
            subtype = raw["subtype"].str(),
            author = jid(raw["author"]) ?: jid(idObj["participant"]),
            recipients = raw["recipients"].arr()?.mapNotNull { jid(it) } ?: emptyList(),
            text = if (b != null && !looksLikeBase64Blob(b)) b.take(1000) else null,
            templateParams = raw["templateParams"].arr()?.map { jid(it) ?: it.jsString() }?.take(20),
        )
    }
    val meta = MessageMeta(
        quoted = quoted,
        location = location,
        vcards = vcards,
        poll = poll,
        call = call,
        mentions = mentions,
        system = system,
        rawType = if (type == MessageType.Unknown) rawType else null,
        invite = if (rawType == "groups_v4_invite") InviteInfo(raw["inviteGrpName"].str()) else null,
        forwardingScore = raw["forwardingScore"].num()?.takeIf { it != 0.0 },
    )

    val media = if (isMedia) {
        MediaFacts(
            mime = raw["mimetype"].str(),
            filename = raw["filename"].str(),
            size = raw["size"].num()?.toLong(),
            width = raw["width"].num(),
            height = raw["height"].num(),
            durationSec = raw["duration"].num(),
        )
    } else {
        null
    }

    val tSec = raw["t"].num() ?: 0.0
    val editKeyEl = raw["latestEditMsgKey"]
    val editKey = if (editKeyEl.truthy()) msgKey(editKeyEl) ?: editKeyEl.obj()?.get("id").str() else null

    return MappedMessage(
        id = id,
        chatId = chatId,
        senderId = senderId,
        fromMe = fromMe,
        ts = (tSec * 1000).roundToLong(),
        type = if (isViewOnce && isMedia) MessageType.ViewOnce else type,
        rawType = rawType,
        body = body,
        meta = meta.takeUnless { it.isEmpty() },
        thumbBase64 = thumb,
        quotedStanzaId = quoted?.stanzaId,
        media = media,
        downloadable = isMedia && hasDirectPath && !isViewOnce,
        isViewOnce = isViewOnce,
        isForwarded = raw["isForwarded"].truthy(),
        isStatus = raw["isStatusV3"].truthy() || chatId == "status@broadcast",
        ack = raw["ack"].num()?.toInt(),
        revoked = rawType == "revoked",
        revokedBy = if (rawType == "revoked") (if (raw["subtype"].str() == "admin") "admin" else "sender") else null,
        editKey = editKey,
        editTs = raw["latestEditSenderTimestampMs"].num()?.toLong(),
        notifyName = raw["notifyName"].str(),
        // Never store a message without its full key (media, quotes and reactions are looked up by it).
        skip = rawType in SKIP_TYPES || id.isEmpty() || !id.contains('_'),
    )
}

/* ------------------------------------------------------- system message text */

fun renderSystemText(sys: SystemInfo, nameOf: (String?) -> String, rawType: String? = null): String {
    val who = nameOf(sys.author)
    val whom = sys.recipients.joinToString(", ") { nameOf(it) }.ifEmpty { "someone" }
    return when (sys.subtype) {
        "add" -> "$who added $whom"
        "invite" -> "$whom joined using an invite link"
        "linked_group_join" -> "$whom joined from the community"
        "remove" -> "$who removed $whom"
        "leave" -> "$whom left"
        "promote" -> "$who made $whom an admin"
        "demote" -> "$who dismissed $whom as admin"
        "create" -> "$who created the group${sys.text?.let { " \"$it\"" } ?: ""}"
        "subject" -> "$who changed the group name${sys.text?.let { " to \"$it\"" } ?: ""}"
        "description" -> "$who changed the group description"
        "picture" -> "$who changed the group icon"
        "announce" -> "$who changed group settings (who can send messages)"
        "restrict" -> "$who changed group settings (who can edit info)"
        "ephemeral" -> "$who changed disappearing messages settings"
        "membership_approval_request" -> "$who requested to join"
        "change_number" -> "$who changed their phone number"
        else -> sys.text ?: if (rawType != null) "System notification ($rawType${sys.subtype?.let { "/$it" } ?: ""})" else "System notification"
    }
}
