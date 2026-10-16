package io.github.revocx35.walmobile.engine

import android.app.Application
import android.net.Uri
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.SendMediaKind
import io.github.revocx35.walmobile.store.Harness
import io.github.revocx35.walmobile.ui.OutgoingFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

private const val CHAT = "905551112233@c.us"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalSenderTest {
    private lateinit var h: Harness
    private val ops = mutableListOf<Pair<String, JsonObject>>()
    private var linked = true
    private var reply: (String, JsonObject) -> JsonElement = { _, _ -> JsonNull }
    private lateinit var sender: LocalSender

    @Before
    fun setUp() {
        h = Harness()
        sender = LocalSender(
            h.app,
            { op, args, _ ->
                ops += op to args
                reply(op, args)
            },
            { linked },
            h.repo,
            h.vault,
            h.files,
            h.settings,
            h.events,
            { },
        )
    }

    @After
    fun tearDown() = h.close()

    private fun names() = ops.map { it.first }

    @Test
    fun sendsTextThenMarksTheChatRead(): Unit = runBlocking {
        sender.sendText(CHAT, "Merhaba\nikinci", "false_${CHAT}_Q")
        assertEquals(listOf("sendText", "markRead"), names())
        assertEquals("Merhaba\nikinci", ops[0].second["text"]!!.jsonPrimitive.content)
        assertEquals("false_${CHAT}_Q", ops[0].second["quotedId"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusesWhileWhatsAppIsNotConnected(): Unit = runBlocking {
        linked = false
        try {
            sender.react("x", "👍")
            fail("expected unavailable")
        } catch (e: ApiException) {
            assertEquals("unavailable", e.code)
        }
        assertTrue(ops.isEmpty())
    }

    @Test
    fun uploadsInChunksAndStoresTheSentFileForItsMessage(): Unit = runBlocking {
        val id = "true_${CHAT}_SENT1"
        // What WhatsApp logs when it adds the message: no file to download yet.
        h.ingest.message(
            Json.parseToJsonElement("""{"id":{"fromMe":true,"remote":"$CHAT","id":"SENT1","_serialized":"$id"},"type":"image","body":"","t":1700000000,"from":"905550000001@c.us","to":"$CHAT","mimetype":"image/jpeg","size":1300000}""").jsonObject,
            Source.Live,
        )
        val data = ByteArray(1_300_000) { (it % 251).toByte() }
        val f = File(h.app.cacheDir, "photo.jpg").apply { writeBytes(data) }
        val received = java.io.ByteArrayOutputStream()
        reply = { op, args ->
            when (op) {
                "uploadBegin" -> JsonPrimitive("tok")
                "uploadChunk" -> {
                    received.write(Base64.getDecoder().decode(args["b64"]!!.jsonPrimitive.content))
                    JsonPrimitive(true)
                }
                "sendMedia" -> JsonObject(mapOf("id" to JsonPrimitive(id)))
                else -> JsonNull
            }
        }
        sender.sendFile(CHAT, OutgoingFile(Uri.fromFile(f), "photo.jpg", "image/jpeg", -1), SendMediaKind.Auto, "deniz", null)
        assertEquals(listOf("uploadBegin", "uploadChunk", "uploadChunk", "uploadChunk", "sendMedia", "markRead"), names())
        assertTrue(received.toByteArray().contentEquals(data))
        val send = ops.first { it.first == "sendMedia" }.second
        assertEquals("auto", send["kind"]!!.jsonPrimitive.content)
        assertEquals("deniz", send["caption"]!!.jsonPrimitive.content)
        val row = h.repo.getMessage(id)!!
        assertEquals("downloaded", row.mediaStatus)
        assertEquals(data.size.toLong(), row.mediaSize)
    }

    @Test
    fun dropsTheUploadWhenSendingFails(): Unit = runBlocking {
        val f = File(h.app.cacheDir, "v.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        reply = { op, _ ->
            when (op) {
                "uploadBegin" -> JsonPrimitive("tok")
                "sendMedia" -> throw PageError("WhatsApp could not send it (error_upload).", "send_failed")
                else -> JsonPrimitive(true)
            }
        }
        try {
            sender.sendFile(CHAT, OutgoingFile(Uri.fromFile(f), "v.ogg", "audio/ogg", 3), SendMediaKind.Voice, null, null)
            fail("expected send_failed")
        } catch (e: ApiException) {
            assertEquals("send_failed" to "WhatsApp could not send it (error_upload).", e.code to e.message)
        }
        assertEquals(listOf("uploadBegin", "uploadChunk", "sendMedia", "uploadDrop"), names())
    }

    @Test
    fun mapsPageRefusalsAndCapsSendsPerHour(): Unit = runBlocking {
        reply = { _, _ -> throw PageError("This message can no longer be deleted for everyone.", "not_allowed") }
        try {
            sender.revoke("m")
            fail("expected conflict")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
        }
        reply = { _, _ -> JsonNull }
        repeat(LocalSender.MAX_PER_HOUR - 1) { sender.react("m", "👍") }
        try {
            sender.react("m", "👍")
            fail("expected rate limit")
        } catch (e: ApiException) {
            assertEquals("rate_limited", e.code)
        }
    }
}
