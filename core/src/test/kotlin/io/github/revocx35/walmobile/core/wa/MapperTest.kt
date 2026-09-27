package io.github.revocx35.walmobile.core.wa

import io.github.revocx35.walmobile.core.CallLogInfo
import io.github.revocx35.walmobile.core.ChatKind
import io.github.revocx35.walmobile.core.LocationInfo
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.core.PollInfo
import io.github.revocx35.walmobile.core.PollOption
import io.github.revocx35.walmobile.core.VcardInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the server's mapper.test.ts (same fixtures). */
class MapperTest {
    private val thumb = "/9j/" + "A".repeat(400)

    private fun raw(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun id(fromMe: Boolean, remote: String, msgId: String, participant: String? = null): String {
        val ser = "${fromMe}_${remote}_$msgId${participant?.let { "_$it" } ?: ""}"
        return """{"fromMe":$fromMe,"remote":"$remote","id":"$msgId"${participant?.let { ",\"participant\":\"$it\"" } ?: ""},"_serialized":"$ser"}"""
    }

    /** base({...}) = { t: 1_700_000_000, ack: 1, ...over } */
    private fun base(fields: String): JsonObject = raw("""{"t":1700000000,"ack":1,$fields}""")

    @Test
    fun helpers() {
        assertEquals("a@c.us", jid(JsonPrimitive("a@c.us")))
        assertEquals("b@c.us", jid(buildJsonObject { put("_serialized", "b@c.us") }))
        assertNull(jid(null))
        assertEquals(ChatKind.Group, chatKind("123@g.us"))
        assertEquals(ChatKind.Status, chatKind("status@broadcast"))
        assertEquals(ChatKind.Broadcast, chatKind("1@broadcast"))
        assertEquals(ChatKind.Newsletter, chatKind("1@newsletter"))
        assertEquals(ChatKind.User, chatKind("905551112233@c.us"))
        assertEquals(ChatKind.User, chatKind("12345@lid"))
        assertEquals("+905551112233", phoneOf("905551112233@c.us"))
        assertNull(phoneOf("12345@lid"))
        assertTrue(looksLikeBase64Blob(thumb))
        assertFalse(looksLikeBase64Blob("hello world"))
    }

    @Test
    fun incomingText() {
        val m = mapMessage(base(""""id":${id(false, "905551112233@c.us", "ABC")},"type":"chat","body":"hi *there*","from":"905551112233@c.us","to":"me@c.us","notifyName":"Ali""""))
        assertEquals("false_905551112233@c.us_ABC", m.id)
        assertEquals("905551112233@c.us", m.chatId)
        assertEquals("905551112233@c.us", m.senderId)
        assertFalse(m.fromMe)
        assertEquals(1_700_000_000_000L, m.ts)
        assertEquals(MessageType.Chat, m.type)
        assertEquals("hi *there*", m.body)
        assertFalse(m.downloadable)
        assertFalse(m.skip)
        assertEquals("Ali", m.notifyName)
        assertEquals(1, m.ack)
    }

    @Test
    fun outgoingHasNoSender() {
        val m = mapMessage(base(""""id":${id(true, "905551112233@c.us", "X1")},"type":"chat","body":"yo","from":"me@c.us","to":"905551112233@c.us""""))
        assertTrue(m.fromMe)
        assertNull(m.senderId)
        assertEquals("905551112233@c.us", m.chatId)
    }

    @Test
    fun groupAuthor() {
        val m = mapMessage(base(""""id":${id(false, "120363@g.us", "G1", "905550000000@c.us")},"type":"chat","body":"hello group","from":"120363@g.us","author":"905550000000@c.us""""))
        assertEquals("120363@g.us", m.chatId)
        assertEquals("905550000000@c.us", m.senderId)
    }

    @Test
    fun imageWithCaption() {
        val m = mapMessage(
            base(""""id":${id(false, "a@c.us", "IMG")},"type":"image","body":"$thumb","caption":"look","mimetype":"image/jpeg","size":12345,"width":800,"height":600,"directPath":"/v/t62/abc","from":"a@c.us""""),
        )
        assertEquals(MessageType.Image, m.type)
        assertEquals("look", m.body)
        assertEquals(thumb, m.thumbBase64)
        assertTrue(m.downloadable)
        assertEquals(MediaFacts("image/jpeg", null, 12345, 800.0, 600.0, null), m.media)
    }

    @Test
    fun thumbnailIsNeverText() {
        assertNull(mapMessage(base(""""id":${id(false, "a@c.us", "V")},"type":"video","body":"$thumb","directPath":"/x","from":"a@c.us"""")).body)
    }

    @Test
    fun otherMedia() {
        assertEquals(MessageType.Gif, mapMessage(base(""""id":${id(false, "a@c.us", "1")},"type":"video","isGif":true,"directPath":"/x"""")).type)
        assertEquals(7.0, mapMessage(base(""""id":${id(false, "a@c.us", "2")},"type":"ptt","directPath":"/x","duration":"7"""")).media?.durationSec)
        val d = mapMessage(base(""""id":${id(false, "a@c.us", "3")},"type":"document","filename":"report.pdf","mimetype":"application/pdf","directPath":"/x""""))
        assertEquals(MessageType.Document, d.type)
        assertEquals("report.pdf", d.media?.filename)
        assertEquals(MessageType.Sticker, mapMessage(base(""""id":${id(false, "a@c.us", "4")},"type":"sticker","directPath":"/x"""")).type)
    }

    @Test
    fun viewOnce() {
        val m = mapMessage(base(""""id":${id(false, "a@c.us", "VO")},"type":"image","isViewOnce":true,"body":"$thumb","directPath":"/x""""))
        assertEquals(MessageType.ViewOnce, m.type)
        assertTrue(m.isViewOnce)
        assertFalse(m.downloadable)
        assertNull(m.thumbBase64)
    }

    @Test
    fun quotedReply() {
        val m = mapMessage(
            base(""""id":${id(false, "a@c.us", "Q")},"type":"chat","body":"reply","quotedMsg":{"type":"image","body":"$thumb","caption":"orig caption"},"quotedStanzaID":"ORIG1","quotedParticipant":{"_serialized":"a@c.us"}"""),
        )
        assertEquals(QuotedInfo("ORIG1", "a@c.us", MessageType.Image, "orig caption"), m.meta?.quoted)
        assertEquals("ORIG1", m.quotedStanzaId)
    }

    @Test
    fun structuredTypes() {
        val loc = mapMessage(base(""""id":${id(false, "a@c.us", "L")},"type":"location","lat":41.0,"lng":29.0,"loc":"Cafe\nMain St 1","body":"$thumb""""))
        assertEquals(MessageType.Location, loc.type)
        assertEquals(LocationInfo(41.0, 29.0, "Cafe", "Main St 1", null), loc.meta?.location)
        assertNull(loc.body)
        assertEquals(MessageType.LiveLocation, mapMessage(base(""""id":${id(false, "a@c.us", "LL")},"type":"location","isLive":true,"lat":1,"lng":2""")).type)
        val vc = mapMessage(base(""""id":${id(false, "a@c.us", "VC")},"type":"vcard","body":"BEGIN:VCARD\nFN:Jane Doe\nEND:VCARD""""))
        assertEquals(listOf(VcardInfo("Jane Doe", "BEGIN:VCARD\nFN:Jane Doe\nEND:VCARD")), vc.meta?.vcards)
        assertNull(vc.body)
        val mv = mapMessage(base(""""id":${id(false, "a@c.us", "MV")},"type":"multi_vcard","vcardList":[{"displayName":"A","vcard":"x"},{"vcard":"FN:B"}]"""))
        assertEquals(listOf("A", "B"), mv.meta?.vcards?.map { it.displayName })
        val poll = mapMessage(base(""""id":${id(false, "a@c.us", "P")},"type":"poll_creation","pollName":"Lunch?","pollOptions":[{"name":"Yes"},{"name":"No"}],"pollSelectableOptionsCount":0"""))
        assertEquals(MessageType.Poll, poll.type)
        assertEquals("Lunch?", poll.body)
        assertEquals(PollInfo("Lunch?", listOf(PollOption("Yes", null), PollOption("No", null)), true), poll.meta?.poll)
        val call = mapMessage(base(""""id":${id(false, "a@c.us", "C")},"type":"call_log","isVideoCall":true,"callOutcome":"missed""""))
        assertEquals(MessageType.CallLog, call.type)
        assertEquals(CallLogInfo(true, "missed", null), call.meta?.call)
    }

    @Test
    fun revoked() {
        val m = mapMessage(base(""""id":${id(false, "a@c.us", "R")},"type":"revoked","subtype":"sender""""))
        assertTrue(m.revoked)
        assertEquals("sender", m.revokedBy)
        assertEquals("admin", mapMessage(base(""""id":${id(false, "g@g.us", "R2")},"type":"revoked","subtype":"admin"""")).revokedBy)
    }

    @Test
    fun editKeys() {
        val m = mapMessage(base(""""id":${id(false, "a@c.us", "E")},"type":"chat","body":"v2","latestEditMsgKey":{"_serialized":"false_a@c.us_EDIT1"},"latestEditSenderTimestampMs":1700000100000"""))
        assertEquals("false_a@c.us_EDIT1", m.editKey)
        assertEquals(1_700_000_100_000L, m.editTs)
    }

    @Test
    fun systemAndNoise() {
        val s = mapMessage(base(""""id":${id(false, "g@g.us", "S")},"type":"gp2","subtype":"add","author":"x@c.us","recipients":["y@c.us",{"_serialized":"z@c.us"}]"""))
        assertEquals(MessageType.System, s.type)
        assertEquals("add", s.meta?.system?.subtype)
        assertEquals("x@c.us", s.meta?.system?.author)
        assertEquals(listOf("y@c.us", "z@c.us"), s.meta?.system?.recipients)
        for (t in listOf("protocol", "e2e_notification", "reaction", "debug")) {
            assertTrue(t, mapMessage(base(""""id":${id(false, "a@c.us", t)},"type":"$t"""")).skip)
        }
    }

    @Test
    fun unknownKeepsRawType() {
        val m = mapMessage(base(""""id":${id(false, "a@c.us", "U")},"type":"interactive","body":"Pick one""""))
        assertEquals(MessageType.Unknown, m.type)
        assertEquals("Pick one", m.body)
        assertEquals("interactive", m.meta?.rawType)
    }

    @Test
    fun statusUpdates() {
        val m = mapMessage(base(""""id":${id(false, "status@broadcast", "ST", "p@c.us")},"type":"image","author":"p@c.us","isStatusV3":true,"directPath":"/x""""))
        assertTrue(m.isStatus)
        assertEquals("status@broadcast", m.chatId)
        assertEquals("p@c.us", m.senderId)
    }

    @Test
    fun rebuildsKeysWithoutSerialized() {
        val inGroup = mapMessage(raw("""{"id":{"fromMe":false,"remote":"120363@g.us","id":"ABC","participant":{"_serialized":"123@lid"}},"type":"chat","body":"x","t":1}"""))
        assertEquals("false_120363@g.us_ABC_123@lid", inGroup.id)
        val own = mapMessage(raw("""{"id":{"fromMe":true,"remote":"555@lid","id":"DEF","self":"out"},"type":"chat","body":"y","t":1}"""))
        assertEquals("true_555@lid_DEF_out", own.id)
        val oneToOne = mapMessage(raw("""{"id":{"fromMe":false,"remote":"555@lid","id":"GHI"},"type":"chat","body":"z","t":1}"""))
        assertEquals("false_555@lid_GHI", oneToOne.id)
        assertFalse(oneToOne.skip)
        assertTrue(mapMessage(raw("""{"id":{"id":"ONLYSTANZA"},"type":"chat","body":"x","t":1}""")).skip)
    }

    @Test
    fun systemText() {
        val names = mapOf("x@c.us" to "Xena", "y@c.us" to "Yusuf")
        val nameOf: (String?) -> String = { id -> if (id != null) names[id] ?: id else "Someone" }
        assertEquals("Xena added Yusuf", renderSystemText(SystemInfo("add", "x@c.us", listOf("y@c.us"), null), nameOf))
        assertEquals("Yusuf left", renderSystemText(SystemInfo("leave", null, listOf("y@c.us"), null), nameOf))
        assertEquals("Xena changed the group name to \"Family\"", renderSystemText(SystemInfo("subject", "x@c.us", emptyList(), "Family"), nameOf))
        assertEquals("System notification (gp2/weird)", renderSystemText(SystemInfo("weird", null, emptyList(), null), nameOf, "gp2"))
    }

    @Test
    fun metaSerializesCompactly() {
        val m = mapMessage(base(""""id":${id(false, "a@c.us", "Q")},"type":"chat","body":"reply","quotedMsg":{"type":"chat","body":"orig"},"quotedStanzaID":"S1""""))
        val json = io.github.revocx35.walmobile.core.WaJson.encodeToString(MessageMeta.serializer(), m.meta!!)
        assertEquals("""{"quoted":{"stanzaId":"S1","type":"chat","text":"orig"}}""", json)
        assertEquals(m.meta, io.github.revocx35.walmobile.core.WaJson.decodeFromString(MessageMeta.serializer(), json))
    }
}
