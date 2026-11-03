package io.github.revocx35.walmobile.engine

import io.github.revocx35.walmobile.store.Repo

/** Digits of a phone number as typed or stored ("+90 555 999 00 01", "0090…" → "905559990001"); null when it can't be one. */
fun normalizePhone(v: String): String? {
    val digits = v.filter { it.isDigit() }.removePrefix("00")
    return digits.takeIf { Regex("^[1-9]\\d{4,19}$").matches(it) }
}

private val PHONE_JID = Regex("^(\\d{5,20})@(c\\.us|s\\.whatsapp\\.net)$")

/** Phone digits of a phone-number JID ("905551112233@c.us"); null for LIDs and everything that is not a person. */
fun phoneDigits(id: String): String? = PHONE_JID.find(id)?.groupValues?.get(1)

private fun isPersonId(id: String) = id.endsWith("@c.us") || id.endsWith("@s.whatsapp.net") || id.endsWith("@lid")

/**
 * The owner's list of contacts that are not logged (port of the server's `wa/blocklist.ts`), matched by phone number,
 * never by name (which the person can change at will). A "…@lid" id hides the number; `contact_directory` remembers
 * which number a LID belongs to (numbers only, like the digits already in every chat id) so the engine can match it
 * without asking the WhatsApp page and without the key (the engine runs while the app is locked).
 */
class BlockList(private val repo: Repo) {
    @Volatile private var phones: Set<String> = repo.listBlocked().toSet()
    private val lidPhones = HashMap<String, String>()

    /** Nothing is blocked: every check can be skipped. */
    val active: Boolean get() = phones.isNotEmpty()

    fun list(): List<String> = phones.toList()

    fun replace(newPhones: Collection<String>) {
        repo.setBlocked(newPhones.mapNotNull { normalizePhone(it) }.toSet(), System.currentTimeMillis())
        phones = repo.listBlocked().toSet()
    }

    /** The phone number behind a WhatsApp id, as far as it is known without asking the page. */
    fun phoneOf(id: String?): String? {
        if (id.isNullOrEmpty()) return null
        phoneDigits(id)?.let { return it }
        if (!id.endsWith("@lid")) return null
        synchronized(lidPhones) { lidPhones[id] }?.let { return it }
        return repo.getDirectoryPhone(id)?.also { synchronized(lidPhones) { lidPhones[id] = it } }
    }

    /** True when [id] is a blocked person (a LID whose number is not known yet counts as not blocked: see [unresolved]). */
    fun blocks(id: String?): Boolean {
        if (!active || id.isNullOrEmpty()) return false
        val p = phoneOf(id) ?: return false
        return p in phones
    }

    /** A person id whose number is unknown: while anything is blocked it can't be cleared, so callers decide how to treat it. */
    fun unresolved(id: String?): Boolean = active && !id.isNullOrEmpty() && id.endsWith("@lid") && phoneOf(id) == null

    /** Remembers whose number a WhatsApp id is. */
    fun record(id: String, phone: String?) {
        if (!isPersonId(id)) return
        val digits = phone?.let { normalizePhone(it) } ?: phoneDigits(id) ?: return
        repo.upsertDirectory(id, digits, System.currentTimeMillis())
        if (id.endsWith("@lid")) synchronized(lidPhones) { lidPhones[id] = digits }
    }
}
