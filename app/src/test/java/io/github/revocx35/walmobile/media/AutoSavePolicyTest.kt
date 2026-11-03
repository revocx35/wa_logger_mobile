package io.github.revocx35.walmobile.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSavePolicyTest {
    private fun wants(type: String, status: Boolean = false, chat: String = "905551112233@c.us", sec: Int? = null, videos: Boolean = false, st: Boolean = false, only: Boolean = false) =
        AutoSavePolicy.wants(type, status, chat, sec, videos, st, only)

    @Test
    fun photosAlwaysVideosOnlyWhenAsked() {
        assertTrue(wants("image"))
        assertFalse(wants("video"))
        assertFalse(wants("gif"))
        assertTrue(wants("video", videos = true))
        assertTrue(wants("gif", videos = true))
    }

    @Test
    fun otherTypesAreNeverSaved() {
        for (t in listOf("chat", "ptt", "audio", "document", "sticker", "view_once")) assertFalse(t, wants(t, videos = true, st = true))
    }

    @Test
    fun statusUpdatesNeedTheirOwnOption() {
        assertFalse(wants("image", status = true))
        assertFalse(wants("image", chat = "status@broadcast"))
        assertTrue(wants("image", status = true, st = true))
        assertTrue(wants("image", chat = "status@broadcast", st = true))
    }

    @Test
    fun onlyDisappearingMessagesWhenAsked() {
        assertFalse(wants("image", only = true))
        assertFalse(wants("image", sec = 0, only = true))
        assertTrue(wants("image", sec = 86400, only = true))
        assertTrue(wants("image", sec = 86400))
    }
}
