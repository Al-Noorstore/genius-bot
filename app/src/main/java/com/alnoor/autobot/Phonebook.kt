package com.alnoor.autobot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Phone ke saved contacts padho (READ_CONTACTS). */
object Phonebook {

    data class Entry(val name: String, val number: String)

    /** v4.22: contact ko ANDROID PHONE BOOK mein bhi save karta hai (WRITE_CONTACTS permission ke sath). */
    fun insertContact(ctx: Context, name: String, phone: String): Boolean {
        try {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
            val cr = ctx.contentResolver
            val rawUri = cr.insert(ContactsContract.RawContacts.CONTENT_URI, android.content.ContentValues()) ?: return false
            val rawId = ContactsContract.RawContacts::class.let { android.content.ContentUris.parseId(rawUri) }
            val cvName = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            cr.insert(ContactsContract.Data.CONTENT_URI, cvName)
            val cvPhone = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            cr.insert(ContactsContract.Data.CONTENT_URI, cvPhone)
            return true
        } catch (_: Exception) { return false }
    }

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun all(ctx: Context, limit: Int = 500): List<Entry> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<Entry>()
        try {
            val cur = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return emptyList()
            cur.use {
                val ni = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && out.size < limit) {
                    val name = if (ni >= 0) it.getString(ni) ?: continue else continue
                    val num = if (pi >= 0) it.getString(pi)?.replace(" ", "")?.replace("-", "") ?: continue else continue
                    if (name.isNotBlank() && num.length >= 7) out.add(Entry(name.trim(), num))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * Smart offline contact matching. Normalizes common Roman-Urdu/English spelling
     * variants, ranks exact/token/prefix/substring/fuzzy matches, and returns several
     * close candidates so callers can ask the user instead of silently guessing.
     */
    fun normalizeName(value: String): String {
        var s = value.lowercase(java.util.Locale.ROOT)
            .replace("khaliq", "khalik")
            .replace("khaleq", "khalik")
            .replace("khalek", "khalik")
            .replace("q", "k")
            .replace("ph", "f")
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("ii", "i")
            .replace("oo", "u")
            .replace("ou", "u")
        s = s.replace(Regex("[^\\p{L}\\p{M}0-9]+"), " ").trim()
        return s.replace(Regex("\\s+"), " ")
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun matchScore(query: String, name: String): Int {
        val q = normalizeName(query)
        val n = normalizeName(name)
        if (q.isBlank() || n.isBlank()) return 0
        if (q == n) return 100
        if (n.startsWith(q) || q.startsWith(n)) return 88
        if (n.contains(q) || q.contains(n)) return 82
        val qt = q.split(' ').filter { it.isNotBlank() }
        val nt = n.split(' ').filter { it.isNotBlank() }
        val overlap = qt.count { token -> nt.any { it == token || (minOf(it.length, token.length) >= 4 && editDistance(it, token) <= 1) } }
        if (overlap > 0) {
            val ratio = overlap.toDouble() / maxOf(qt.size, nt.size)
            if (ratio >= 0.5) return (65 + ratio * 20).toInt()
        }
        val dist = editDistance(q, n)
        val maxLen = maxOf(q.length, n.length)
        if (maxLen >= 5 && dist <= maxOf(2, maxLen / 4)) return (70.0 * (1.0 - dist.toDouble() / maxLen)).toInt()
        return 0
    }

    fun findByName(ctx: Context, query: String, max: Int = 8): List<Entry> {
        val q = normalizeName(query)
        if (q.length < 2) return emptyList()
        val entries = all(ctx)
        // Collapse repeated contact rows only when both displayed name and number match.
        val ranked = entries.distinctBy { normalizeName(it.name) + "|" + it.number.replace(Regex("[^0-9+]"), "") }
            .map { it to matchScore(q, it.name) }
            .filter { it.second >= 45 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.name.lowercase(java.util.Locale.ROOT) })
        if (ranked.isEmpty()) return emptyList()
        val top = ranked.first().second
        // If best matches are uncertain/close, keep multiple options for voice/text selection.
        return ranked.filter { it.second >= maxOf(45, top - 12) }.take(max).map { it.first }
    }

    fun listText(ctx: Context, q: String? = null): String {
        if (!hasPermission(ctx)) return "❌ Contacts permission nahi. Settings se allow karo, phir 'contacts' likho."
        val list = if (q.isNullOrBlank()) all(ctx, 40) else findByName(ctx, q, 20)
        if (list.isEmpty()) return if (q.isNullOrBlank()) "📭 Phone book khali / koi contact nahi." else "❌ '$q' se match nahi."
        val sb = StringBuilder("📇 *Phone contacts*\n")
        list.forEachIndexed { i, e -> sb.append("${i + 1}. ${e.name} — ${e.number}\n") }
        if (q.isNullOrBlank() && all(ctx).size > 40) sb.append("… aur bhi hain. Search: contacts Ali\n")
        return sb.toString()
    }
}
