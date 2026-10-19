package com.alnoor.autobot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight offline visual/action memory for low-RAM phones. Stores semantic labels and capture context, never uploads data. */
object VisualMemory {
    private const val PREF = "genius_visual_memory_v1"
    private const val KEY = "anchors"
    data class Anchor(val name: String, val label: String, val packageName: String, val screenshot: String, val created: Long)
    private fun norm(s: String) = s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()
    private fun load(ctx: Context): MutableList<Anchor> {
        val arr = try { JSONArray(ctx.getSharedPreferences(PREF, 0).getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val out = mutableListOf<Anchor>()
        for (i in 0 until arr.length()) try { val o=arr.getJSONObject(i); out.add(Anchor(o.optString("name"),o.optString("label"),o.optString("pkg"),o.optString("shot"),o.optLong("created"))) } catch (_: Exception) {}
        return out
    }
    private fun save(ctx: Context, rows: List<Anchor>) { val a=JSONArray(); rows.takeLast(40).forEach { a.put(JSONObject().put("name",it.name).put("label",it.label).put("pkg",it.packageName).put("shot",it.screenshot).put("created",it.created)) }; ctx.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply() }
    fun teach(ctx: Context, name: String, label: String): String {
        val n=name.trim().take(70); val l=label.trim().take(100)
        if (n.length<2 || l.isBlank()) return "Use: remember button <name> as <visible text>"
        val shot=ctx.getSharedPreferences("genius_visual",0).getString("last_capture","") ?: ""
        val rows=load(ctx); rows.removeAll { norm(it.name)==norm(n) }
        rows.add(Anchor(n,l,AutoBotAccessibilityService.foregroundPackage(),shot,System.currentTimeMillis())); save(ctx,rows)
        return "Visual memory saved locally: '$n' is matched by visible label '$l' in app '${AutoBotAccessibilityService.foregroundPackage().ifBlank { "unknown app" }}'. Screenshot reference stored if available. Matching tolerates punctuation/case changes, but still requires an accessible text/description label; it does not recognize icon-only controls."
    }
    fun list(ctx: Context): String { val rows=load(ctx); return if(rows.isEmpty()) "No visual memories saved yet." else "Saved visual memories:\n"+rows.joinToString("\n") { "• ${it.name} → ${it.label} (${it.packageName.ifBlank { "any app" }})" } }
    fun forget(ctx: Context, name: String): String { val r=load(ctx); val n=r.removeAll { norm(it.name)==norm(name) }; save(ctx,r); return if(n) "Removed visual memory '$name'." else "Visual memory '$name' not found." }
    fun find(ctx: Context, name: String): Anchor? = load(ctx).firstOrNull { norm(it.name)==norm(name) }
}
