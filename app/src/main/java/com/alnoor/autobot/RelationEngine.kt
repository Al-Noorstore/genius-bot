package com.alnoor.autobot

import android.content.Context

/**
 * v4.22 RelationEngine: naam / relation / number ka shared resolver.
 * Priority: (1) direct number, (2) OfflineBrain saved contacts (relations FIRST —
 * saved "abu" ko phonebook ke "Abu Huraira" par override nahi hone dena),
 * (3) Android phonebook (exact name pehle), warna choice list.
 */
object RelationEngine {

    sealed class Res {
        data class Direct(val number: String) : Res()
        data class Single(val label: String, val number: String) : Res()
        data class Ambiguous(val options: List<Pair<String, String>>) : Res()
        object NotFound : Res()
    }

    fun isPhoneNumber(t: String): Boolean {
        val digits = t.filter { it.isDigit() }
        return digits.length >= 7 && t.replace(Regex("[+\\d()\\s-]"), "").isBlank()
    }

    fun normalize(p: String): String {
        val n = OfflineBrain.normalizePhone(p)
        return n.ifBlank { p }
    }

    fun resolve(ctx: Context, target: String): Res {
        val t = target.trim().trim('.', '!', '?')
        if (t.isBlank()) return Res.NotFound
        if (isPhoneNumber(t)) return Res.Direct(normalize(t))

        // 1) Brain-saved relations/names pehle — user ne khud bataya hai, sab se trusted
        try {
            val brain = OfflineBrain.loadContacts(ctx)
            val hits = OfflineBrain.resolve(brain, t)
            if (hits.size == 1) return Res.Single(hits[0].name, hits[0].phone)
            if (hits.size > 1) {
                val exact = hits.filter { it.name.equals(t, ignoreCase = true) }
                if (exact.size == 1) return Res.Single(exact[0].name, exact[0].phone)
                return Res.Ambiguous(hits.map { it.name to it.phone })
            }
        } catch (_: Exception) {}

        // 2) Android phonebook — exact naam pehle
        try {
            if (Phonebook.hasPermission(ctx)) {
                val pb = Phonebook.findByName(ctx, t, 5)
                if (pb.isNotEmpty()) {
                    val exact = pb.filter { it.name.equals(t, ignoreCase = true) }
                    if (exact.size == 1) return Res.Single(exact[0].name, exact[0].number)
                    if (pb.size == 1) return Res.Single(pb[0].name, pb[0].number)
                    return Res.Ambiguous(pb.map { it.name to it.number })
                }
            }
        } catch (_: Exception) {}

        return Res.NotFound
    }
}
