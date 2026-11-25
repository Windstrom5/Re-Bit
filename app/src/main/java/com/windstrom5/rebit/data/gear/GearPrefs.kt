package com.windstrom5.rebit.data.gear

import android.content.Context

/** "My setup": the listener's headphones / DAC / amp, shown on Home and on the Wrapped summary. */
data class Gear(val headphones: String = "", val dac: String = "", val amp: String = "") {
    val isEmpty: Boolean get() = headphones.isBlank() && dac.isBlank() && amp.isBlank()

    /** "HD 800 S · Topping E30 · SMSL SP200", skipping blanks. */
    val summary: String
        get() = listOf(headphones, dac, amp).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
}

class GearPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gear", Context.MODE_PRIVATE)

    fun load() = Gear(
        headphones = prefs.getString("headphones", "").orEmpty(),
        dac = prefs.getString("dac", "").orEmpty(),
        amp = prefs.getString("amp", "").orEmpty()
    )

    fun save(g: Gear) {
        prefs.edit()
            .putString("headphones", g.headphones.trim().take(60))
            .putString("dac", g.dac.trim().take(60))
            .putString("amp", g.amp.trim().take(60))
            .apply()
    }
}
