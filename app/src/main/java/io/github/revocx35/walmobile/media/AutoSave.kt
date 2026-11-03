package io.github.revocx35.walmobile.media

import android.content.Context
import android.os.Build
import io.github.revocx35.walmobile.core.MessageType
import io.github.revocx35.walmobile.data.Prefs
import io.github.revocx35.walmobile.engine.MediaTee
import io.github.revocx35.walmobile.engine.TeeHandle
import io.github.revocx35.walmobile.engine.TeeInfo
import io.github.revocx35.walmobile.engine.db

/** Which media the owner wants copied to the gallery. Pure, so it is unit-tested. */
object AutoSavePolicy {
    fun wants(type: String, isStatus: Boolean, chatId: String, disappearingSec: Int?, videos: Boolean, status: Boolean, onlyDisappearing: Boolean): Boolean {
        if (!status && (isStatus || chatId == "status@broadcast")) return false
        // WhatsApp itself never saves disappearing-message media to the gallery: this fills exactly that gap.
        if (onlyDisappearing && (disappearingSec ?: 0) <= 0) return false
        return when (type) {
            "image" -> true
            "video", "gif" -> videos
            else -> false
        }
    }
}

/**
 * "Save new photos to this phone": copies photos (optionally videos/GIFs and status updates) into the shared
 * Pictures/Movies collections (MediaStore, no storage permission on Android 10+) at the moment the engine downloads
 * them, so it works with the app locked and needs no key. Only media downloaded after the owner turns it on is
 * saved. The copies leave the encrypted store on purpose; the setting is off by default and says so.
 */
class AutoSaver(private val context: Context, private val prefs: Prefs) : MediaTee {
    override fun open(info: TeeInfo): TeeHandle? {
        if (!SUPPORTED || !prefs.autoSave.value) return null
        if (!AutoSavePolicy.wants(info.type, info.isStatus, info.chatId, info.disappearingSec, prefs.autoSaveVideos.value, prefs.autoSaveStatus.value, prefs.autoSaveOnlyDisappearing.value)) return null
        val type = MessageType.entries.firstOrNull { it.db() == info.type } ?: return null
        val file = MediaOps.GalleryFile(context, type, info.ts, info.mime, info.filename)
        return object : TeeHandle {
            override fun write(bytes: ByteArray) = file.out.write(bytes)
            override fun commit() {
                file.commit()
                prefs.addAutoSaved()
            }
            override fun abort() = file.abort()
        }
    }

    companion object {
        val SUPPORTED = Build.VERSION.SDK_INT >= 29
    }
}
