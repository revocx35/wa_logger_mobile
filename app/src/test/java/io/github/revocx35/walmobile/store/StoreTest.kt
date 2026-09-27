package io.github.revocx35.walmobile.store

import android.app.Application
import io.github.revocx35.walmobile.core.ApiException
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.core.ServerEvent
import io.github.revocx35.walmobile.core.SettingsPatch
import io.github.revocx35.walmobile.core.WaState
import io.github.revocx35.walmobile.core.WaStatus
import io.github.revocx35.walmobile.core.WaJson
import io.github.revocx35.walmobile.core.crypto.F
import io.github.revocx35.walmobile.core.wa.MessageMeta
import io.github.revocx35.walmobile.engine.ChatLite
import io.github.revocx35.walmobile.engine.ContactInfo
import io.github.revocx35.walmobile.engine.Ingest
import io.github.revocx35.walmobile.engine.MediaSink
import io.github.revocx35.walmobile.engine.PageApi
import io.github.revocx35.walmobile.engine.Priority
import io.github.revocx35.walmobile.engine.Source
import io.github.revocx35.walmobile.security.Vault
import io.github.revocx35.walmobile.security.VaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

private val THUMB = "/9j/" + "B".repeat(400)
private const val CHAT = "905551112233@c.us"
private const val GROUP = "120363000000@g.us"
const val TEST_PASSWORD = "correct horse battery"

/** A fresh in-memory database, unlocked vault and ingest (the Kotlin twin of the server's test harness). */
class Harness {
    val app: Application = RuntimeEnvironment.getApplication()
    val db = Db(app, null)
    val repo = Repo(db)
    val vault = Vault(repo, logN = 4)
    val settings = SettingsStore(repo)
    val events = EventBus()
    val enqueued = mutableListOf<String>()
    val emitted = mutableListOf<ServerEvent>()
    val mediaDir: File = File(app.cacheDir, "media-test-${System.nanoTime()}")
    val files = MediaFiles(mediaDir, repo) { null }
    val api = object : PageApi {
        override suspend fun chatInfo(chatId: String) = ChatLite(chatId, if (chatId == GROUP) "Family" else "Ali Veli", 1_700_000_000_000, false, false, false)
        override suspend fun contactInfo(id: String) = ContactInfo(if (id.startsWith("9055")) "Ali Veli" else null, "ali", null, false, false)
    }
    val ingest = Ingest(repo, vault, settings, events, api, object : MediaSink {
        override fun enqueue(messageId: String, priority: Priority) {
            enqueued += messageId
        }
    })
    var retried = mutableListOf<String>()
    val wa = object : WaControl {
        override val status: StateFlow<WaStatus> = MutableStateFlow(WaStatus(WaState.Ready))
        override suspend fun restart() {}
        override suspend fun logout() {}
        override fun retryMedia(messageId: String): Boolean {
            retried += messageId
            return true
        }
        override fun onDataWiped() {}
    }
    val local = LocalApi(repo, vault, settings, wa, events, files)

    init {
        runBlocking {
            vault.load()
            vault.create(TEST_PASSWORD)
        }
    }

    fun bodyOf(id: String): String? = vault.reader().tryText(F.body(id), repo.getMessage(id)?.bodyEnc)

    fun close() {
        db.close()
        mediaDir.deleteRecursively()
    }
}

private fun rid(msgId: String, remote: String = CHAT, fromMe: Boolean = false, participant: String? = null): String {
    val ser = "${fromMe}_${remote}_$msgId${participant?.let { "_$it" } ?: ""}"
    return """{"fromMe":$fromMe,"remote":"$remote","id":"$msgId"${participant?.let { ",\"participant\":\"$it\"" } ?: ""},"_serialized":"$ser"}"""
}

private fun raw(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

/** text('A1', 'body', extra fields) like the server tests. */
private fun text(msgId: String, body: String, extra: String = "", id: String = rid(msgId)): JsonObject =
    raw("""{"id":$id,"type":"chat","body":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(body))},"t":1700000000,"from":"$CHAT","ack":1,"notifyName":"Ali"${if (extra.isNotEmpty()) ",$extra" else ""}}""")

private fun JsonObject.with(vararg fields: Pair<String, String>): JsonObject =
    JsonObject(this + fields.associate { (k, v) -> k to Json.parseToJsonElement(v) })

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IngestTest {
    private lateinit var h: Harness

    @Before
    fun setUp() {
        h = Harness()
    }

    @After
    fun tearDown() = h.close()

    @Test
    fun storesEncryptedCreatesChatAndContactIdempotent(): Unit = runBlocking {
        val id = h.ingest.message(text("A1", "secret hello"), Source.Live)!!
        val row = h.repo.getMessage(id)!!
        assertEquals(CHAT, row.chatId)
        assertFalse(String(row.bodyEnc!!, Charsets.ISO_8859_1).contains("secret hello"))
        assertEquals("secret hello", h.bodyOf(id))
        assertEquals("live", row.source)
        val chat = h.repo.getChat(CHAT)!!
        assertEquals("Ali Veli", h.vault.reader().tryText(F.chatName(CHAT), chat.nameEnc))
        assertEquals(1_700_000_000_000L, chat.lastTs)
        val contact = h.repo.getContactsByIds(listOf(CHAT)).single()
        assertEquals("Ali Veli", h.vault.reader().tryText(F.contactName(CHAT), contact.nameEnc))
        h.ingest.message(text("A1", "secret hello"), Source.Live)
        assertEquals(1, h.repo.listMessagesBefore(CHAT, null, 10).size)
    }

    @Test
    fun mediaQueueSizeCapHistoryAndViewOnce(): Unit = runBlocking {
        h.ingest.message(text("M1", "").with("type" to "\"image\"", "body" to "\"$THUMB\"", "caption" to "\"pic\"", "directPath" to "\"/p\"", "size" to "1000", "mimetype" to "\"image/jpeg\""), Source.Live)
        assertEquals(listOf("false_905551112233@c.us_M1"), h.enqueued)
        val r1 = h.repo.getMessage("false_905551112233@c.us_M1")!!
        assertEquals("pending", r1.mediaStatus)
        assertEquals("pic", h.bodyOf(r1.id))
        assertEquals(THUMB, java.util.Base64.getEncoder().encodeToString(h.vault.reader().decrypt(F.thumb(r1.id), r1.thumbEnc)))

        h.ingest.message(text("M2", "").with("type" to "\"video\"", "directPath" to "\"/p\"", "size" to (500L * 1024 * 1024).toString()), Source.Live)
        assertEquals("too_large", h.repo.getMessage("false_905551112233@c.us_M2")!!.mediaStatus)

        h.settings.update(SettingsPatch(downloadHistoryMedia = false))
        h.ingest.message(text("M3", "").with("type" to "\"image\"", "directPath" to "\"/p\"", "size" to "10"), Source.History)
        assertEquals("skipped", h.repo.getMessage("false_905551112233@c.us_M3")!!.mediaStatus)

        h.ingest.message(text("M4", "").with("type" to "\"image\"", "isViewOnce" to "true", "directPath" to "\"/p\"", "body" to "\"$THUMB\""), Source.Live)
        val vo = h.repo.getMessage("false_905551112233@c.us_M4")!!
        assertEquals("view_once", vo.mediaStatus)
        assertNull(vo.thumbEnc)
        assertEquals("view_once", vo.type)
        assertEquals(1, h.enqueued.size)
    }

    @Test
    fun keepsDeletedForEveryone(): Unit = runBlocking {
        val deleted = mutableListOf<String>()
        h.ingest.deletionListener = io.github.revocx35.walmobile.engine.DeletionListener { _, id -> deleted += id }
        val id = h.ingest.message(text("D1", "will be deleted"), Source.Live)!!
        h.ingest.revoke(text("D1", "").with("type" to "\"revoked\"", "subtype" to "\"sender\""), null)
        val row = h.repo.getMessage(id)!!
        assertTrue(row.deletedAt!! > 0)
        assertEquals("sender", row.deletedBy)
        assertEquals("will be deleted", h.bodyOf(id))
        assertEquals("chat", row.type)
        assertEquals(listOf(id), deleted)
        // Reconcile seeing the revoked version does not wipe anything (and does not notify twice).
        h.ingest.message(text("D1", "").with("type" to "\"revoked\"", "subtype" to "\"sender\""), Source.Live)
        assertEquals("will be deleted", h.bodyOf(id))
        assertEquals(1, deleted.size)
    }

    @Test
    fun recoversOriginalFromBefore(): Unit = runBlocking {
        h.ingest.revoke(text("D2", "").with("type" to "\"revoked\"", "subtype" to "\"sender\""), text("D2", "original text"))
        val id = "false_905551112233@c.us_D2"
        assertTrue(h.repo.getMessage(id)!!.deletedAt!! > 0)
        assertEquals("original text", h.bodyOf(id))
    }

    @Test
    fun stubForNeverLoggedRevokeThenFilledByHistory(): Unit = runBlocking {
        h.ingest.revoke(text("D3", "").with("type" to "\"revoked\"", "subtype" to "\"admin\""), null)
        val row = h.repo.getMessage("false_905551112233@c.us_D3")!!
        assertEquals("revoked", row.type)
        assertEquals("admin", row.deletedBy)
        assertNull(row.bodyEnc)
        h.ingest.message(text("D3", "late original"), Source.History)
        val filled = h.repo.getMessage("false_905551112233@c.us_D3")!!
        assertEquals("late original", h.bodyOf(filled.id))
        assertEquals(row.deletedAt, filled.deletedAt)
    }

    @Test
    fun editsWithHistoryOncePerKey(): Unit = runBlocking {
        val id = h.ingest.message(text("E1", "first"), Source.Live)!!
        val edited = text("E1", "second", "\"latestEditMsgKey\":{\"_serialized\":\"EK1\"},\"latestEditSenderTimestampMs\":1700000050000")
        h.ingest.edit(edited, "second", "first", false)
        h.ingest.edit(edited, "second", "first", false)
        h.ingest.message(edited, Source.Live)
        assertEquals("second", h.bodyOf(id))
        val edits = h.repo.listEdits(id)
        assertEquals(1, edits.size)
        assertEquals("first", h.vault.reader().tryText(F.body(id), edits[0].bodyEnc))
        assertEquals(1_700_000_050_000L, h.repo.getMessage(id)!!.editedAt)
        h.ingest.message(text("E1", "third", "\"latestEditMsgKey\":{\"_serialized\":\"EK2\"}"), Source.Live)
        assertEquals("third", h.bodyOf(id))
        assertEquals(2, h.repo.listEdits(id).size)
    }

    @Test
    fun ignoresThumbnailOnlyChanges(): Unit = runBlocking {
        val raw = text("E2", "").with("type" to "\"image\"", "body" to "\"$THUMB\"", "caption" to "\"cap\"", "directPath" to "\"/p\"")
        val id = h.ingest.message(raw, Source.Live)!!
        h.ingest.edit(raw, null, null, blob = true)
        h.ingest.edit(raw, THUMB.replaceFirst('B', 'C'), THUMB, blob = false)
        assertEquals(0, h.repo.listEdits(id).size)
        assertEquals("cap", h.bodyOf(id))
    }

    @Test
    fun fillsCiphertextPlaceholders(): Unit = runBlocking {
        val id = h.ingest.message(text("C1", "").with("type" to "\"ciphertext\""), Source.Live)!!
        assertEquals("ciphertext", h.repo.getMessage(id)!!.type)
        h.ingest.message(text("C1", "now readable"), Source.Live)
        assertEquals("chat", h.repo.getMessage(id)!!.type)
        assertEquals("now readable", h.bodyOf(id))
    }

    @Test
    fun reactionsIncludingRemoval(): Unit = runBlocking {
        val id = h.ingest.message(text("R1", "react to me"), Source.Live)!!
        h.ingest.reaction(id, "x@c.us", "👍", 1_700_000_001.0)
        var rs = h.repo.reactionsFor(listOf(id))
        assertEquals(1, rs.size)
        assertEquals("👍", h.vault.reader().tryText(F.reaction(id, "x@c.us"), rs[0].emojiEnc))
        assertEquals(1_700_000_001_000L, rs[0].ts)
        h.ingest.reaction(id, "x@c.us", "", 1_700_000_002_000.0)
        rs = h.repo.reactionsFor(listOf(id))
        assertNotNull(rs[0].removedAt)
        // An older event never overwrites a newer one.
        h.ingest.reaction(id, "x@c.us", "😂", 1_700_000_001_500.0)
        assertNotNull(h.repo.reactionsFor(listOf(id))[0].removedAt)
    }

    @Test
    fun deletedForMeAndMonotonicAcks(): Unit = runBlocking {
        val id = h.ingest.message(text("X1", "mine", id = rid("X1", CHAT, true)), Source.Live)!!
        h.ingest.ack(id, 3)
        h.ingest.ack(id, 2)
        assertEquals(3, h.repo.getMessage(id)!!.ack)
        h.ingest.revokeMe(id)
        val row = h.repo.getMessage(id)!!
        assertNotNull(row.deletedForMeAt)
        assertEquals("mine", h.bodyOf(id))
        assertTrue(row.fromMe)
        assertNull(row.senderId)
    }

    @Test
    fun groupNotificationsAreSystemMessages(): Unit = runBlocking {
        h.ingest.message(
            raw("""{"id":${rid("N1", GROUP, false, "x@c.us")},"type":"gp2","subtype":"add","t":1700000100,"author":"x@c.us","recipients":["y@c.us"]}"""),
            Source.Live,
        )
        val rows = h.repo.listMessagesBefore(GROUP, null, 10)
        assertEquals(1, rows.size)
        assertEquals("system", rows[0].type)
        val meta = WaJson.decodeFromString(MessageMeta.serializer(), h.vault.reader().tryText(F.meta(rows[0].id), rows[0].metaEnc)!!)
        assertEquals("add", meta.system?.subtype)
        assertEquals("Family", h.vault.reader().tryText(F.chatName(GROUP), h.repo.getChat(GROUP)!!.nameEnc))
    }

    @Test
    fun statusUpdatesFollowTheSetting(): Unit = runBlocking {
        h.settings.update(SettingsPatch(logStatus = false))
        val story = """{"id":${rid("S1", "status@broadcast", false, "p@c.us")},"type":"chat","body":"story","t":1,"author":"p@c.us","isStatusV3":true}"""
        assertNull(h.ingest.message(raw(story), Source.Live))
        h.settings.update(SettingsPatch(logStatus = true))
        h.ingest.message(raw(story.replace("S1", "S2")), Source.Live)
        assertEquals("Status updates", h.vault.reader().tryText(F.chatName("status@broadcast"), h.repo.getChat("status@broadcast")!!.nameEnc))
    }

    @Test
    fun removedAndArchivedChats(): Unit = runBlocking {
        h.ingest.message(text("Z1", "x"), Source.Live)
        h.ingest.chatArchived(CHAT, true)
        assertTrue(h.repo.getChat(CHAT)!!.archived)
        h.ingest.chatRemoved(CHAT)
        assertNotNull(h.repo.getChat(CHAT)!!.removedAt)
        assertEquals(1, h.repo.listMessagesBefore(CHAT, null, 10).size)
    }

    @Test
    fun writesWhileLocked(): Unit = runBlocking {
        h.vault.lock()
        assertEquals(VaultState.Locked, h.vault.state.value)
        val id = h.ingest.message(text("L1", "arrived while locked"), Source.Live)!!
        try {
            h.bodyOf(id)
            fail("reading must need the unlocked vault")
        } catch (e: ApiException) {
            assertEquals("locked", e.code)
        }
        h.vault.unlock(TEST_PASSWORD)
        assertEquals("arrived while locked", h.bodyOf(id))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VaultTest {
    private lateinit var h: Harness

    @Before
    fun setUp() {
        h = Harness()
    }

    @After
    fun tearDown() = h.close()

    private suspend fun expectCode(code: String, block: suspend () -> Unit): ApiException {
        try {
            block()
        } catch (e: ApiException) {
            assertEquals(code, e.code)
            return e
        }
        throw AssertionError("expected $code")
    }

    @Test
    fun unlockLockAndWrongPassword(): Unit = runBlocking {
        assertEquals(VaultState.Unlocked, h.vault.state.value)
        h.vault.lock()
        expectCode("locked") { h.vault.reader() }
        expectCode("invalid_credentials") { h.vault.unlock("wrong password") }
        h.vault.unlock(TEST_PASSWORD)
        assertEquals(VaultState.Unlocked, h.vault.state.value)
        assertEquals(0, h.repo.getOwner()!!.failedUnlocks)
        val events = h.repo.listAudit(10).map { it.event }
        assertTrue(events.contains("unlock_failed"))
        assertTrue(events.contains("unlock"))
    }

    @Test
    fun throttlesAfterFiveFailuresAndSurvivesRestart(): Unit = runBlocking {
        var now = 1_000_000L
        val vault = Vault(h.repo, now = { now }, logN = 4)
        vault.load()
        repeat(Vault.FREE_ATTEMPTS) { expectCode("invalid_credentials") { vault.unlock("nope$it") } }
        // Locked out now, even for the right password; the count is in the database.
        val e = expectCode("rate_limited") { Vault(h.repo, now = { now }, logN = 4).unlock(TEST_PASSWORD) }
        assertEquals(30, e.retryAfter)
        now += 31_000
        vault.unlock(TEST_PASSWORD)
        assertEquals(0, h.repo.getOwner()!!.failedUnlocks)
    }

    @Test
    fun recoveryResetsPasswordAndRotatesKey(): Unit = runBlocking {
        h.vault.lock()
        val newKey = runCatching { h.vault.recover("0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000-0000", "new password 1") }
        assertTrue(newKey.isFailure)
        // Set up a known recovery key by rotating first.
        h.vault.unlock(TEST_PASSWORD)
        val key = h.vault.rotateRecoveryKey(TEST_PASSWORD)
        h.vault.lock()
        val next = h.vault.recover(key.lowercase().replace("-", " "), "brand new pass")
        assertEquals(VaultState.Unlocked, h.vault.state.value)
        assertFalse(next == key)
        h.vault.lock()
        expectCode("invalid_credentials") { h.vault.unlock(TEST_PASSWORD) }
        h.vault.unlock("brand new pass")
        h.vault.lock()
        // The old recovery key no longer works.
        expectCode("invalid_credentials") { h.vault.recover(key, "another pass 1") }
    }

    @Test
    fun changePasswordKeepsData(): Unit = runBlocking {
        val id = h.ingest.message(text("P1", "before change"), Source.Live)!!
        expectCode("invalid_credentials") { h.vault.changePassword("wrong", "whatever 123") }
        expectCode("validation") { h.vault.changePassword(TEST_PASSWORD, "short") }
        h.vault.changePassword(TEST_PASSWORD, "another long one")
        assertFalse(h.repo.getOwner()!!.pwAuthHash.all { it == 0.toByte() })
        h.vault.lock()
        h.vault.unlock("another long one")
        assertEquals("before change", h.bodyOf(id))
    }

    @Test
    fun setupOnlyOnce(): Unit = runBlocking {
        expectCode("conflict") { h.vault.create("some other password") }
        Unit
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalApiTest {
    private lateinit var h: Harness

    @Before
    fun setUp() {
        h = Harness()
    }

    @After
    fun tearDown() = h.close()

    private suspend fun seed(n: Int) {
        for (i in 1..n) h.ingest.message(text("P$i", "message number $i", "\"t\":${1_700_000_000 + i}"), Source.Live)
    }

    @Test
    fun pagesNewestFirstAndAround(): Unit = runBlocking {
        seed(25)
        val first = h.local.messages(CHAT, limit = 10)
        assertEquals((16..25).map { "message number $it" }, first.messages.map { it.text })
        assertNotNull(first.nextBefore)
        val second = h.local.messages(CHAT, first.nextBefore, 10)
        assertEquals((6..15).map { "message number $it" }, second.messages.map { it.text })
        val third = h.local.messages(CHAT, second.nextBefore, 10)
        assertEquals(5, third.messages.size)
        assertNull(third.nextBefore)
        val around = h.local.messagesAround(CHAT, "false_905551112233@c.us_P5", 10)
        assertTrue(around.messages.any { it.id == "false_905551112233@c.us_P5" })
        assertNotNull(around.nextAfter)
        val after = h.local.messagesAfter(CHAT, around.nextAfter!!, 100)
        assertEquals("message number 25", after.messages.last().text)
        assertNull(after.nextAfter)
    }

    @Test
    fun chatListDeletedFeedAndSearch(): Unit = runBlocking {
        seed(3)
        h.ingest.revoke(text("P2", "").with("type" to "\"revoked\""), null)
        val chats = h.local.chats()
        assertEquals(1, chats.size)
        assertEquals("Ali Veli", chats[0].name)
        assertEquals(3, chats[0].messageCount)
        assertEquals(1, chats[0].deletedCount)
        assertEquals("message number 3", chats[0].lastMessage?.text)
        val feed = h.local.deleted()
        assertEquals(listOf("message number 2"), feed.items.map { it.message.text })
        assertEquals("Ali Veli", feed.items[0].chat.name)
        val hits = h.local.search("NUMBER 3")
        assertEquals(listOf("false_905551112233@c.us_P3"), hits.hits.map { it.message.id })
        try {
            h.local.search("x")
            fail()
        } catch (e: ApiException) {
            assertEquals("validation", e.code)
        }
    }

    @Test
    fun readingNeedsUnlock(): Unit = runBlocking {
        seed(1)
        h.vault.lock()
        try {
            h.local.chats()
            fail()
        } catch (e: ApiException) {
            assertEquals("locked", e.code)
        }
    }

    @Test
    fun editsAndMessageDto(): Unit = runBlocking {
        h.ingest.message(text("E1", "first"), Source.Live)
        h.ingest.edit(text("E1", "second", "\"latestEditMsgKey\":{\"_serialized\":\"EK1\"}"), "second", "first", false)
        val m = h.local.message("false_905551112233@c.us_E1")
        assertEquals("second", m.text)
        assertTrue(m.edited)
        assertEquals(1, m.editCount)
        assertEquals(listOf("first"), h.local.edits(m.id).map { it.body })
        assertEquals(MessageType.Chat, m.type)
        assertEquals("Ali Veli", m.senderName)
    }

    @Test
    fun wipeNeedsPasswordAndKeepsOwner(): Unit = runBlocking {
        seed(2)
        try {
            h.local.wipe("wrong")
            fail()
        } catch (e: ApiException) {
            assertEquals("invalid_credentials", e.code)
        }
        h.local.wipe(TEST_PASSWORD)
        assertEquals(0L, h.repo.countMessages())
        assertTrue(h.repo.hasOwner())
        assertTrue(h.local.chats().isEmpty())
    }

    @Test
    fun settingsValidation(): Unit = runBlocking {
        val s = h.local.updateSettings(SettingsPatch(mediaMaxMb = 50, historyPerChat = 10))
        assertEquals(50, s.mediaMaxMb)
        assertEquals(10, h.local.settings().historyPerChat)
        try {
            h.local.updateSettings(SettingsPatch(mediaMaxMb = 0))
            fail()
        } catch (e: ApiException) {
            assertEquals("validation", e.code)
        }
    }

    @Test
    fun mediaFilesRoundTrip(): Unit = runBlocking {
        val w = h.vault.writer()!!
        val p = h.files.begin(w)
        val data = ByteArray(200_000) { (it % 251).toByte() }
        p.writer.write(data)
        val (mediaId, size) = h.files.finish(p, w, "message", "image/png", "x.png")
        assertEquals(200_000L, size)
        val opened = h.files.open(mediaId, h.vault.reader())
        assertEquals("image/png", opened.mime)
        assertEquals("x.png", opened.filename)
        assertTrue(opened.reader.use { it.readAll() }.contentEquals(data))
    }
}
