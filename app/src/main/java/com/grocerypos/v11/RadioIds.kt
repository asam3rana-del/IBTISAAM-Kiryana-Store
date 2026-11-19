package com.grocerypos.v11

import android.view.View

/**
 * NEW (Flutter parity): ids for code-built RadioButtons. Android lint rejects plain numbers as
 * view ids ("Expected resource of type id"), so every screen asks for a stable generated id by a
 * local key instead: `id = RadioIds.of(1001)` / `group.checkedRadioButtonId == RadioIds.of(1002)`.
 */
object RadioIds {
    private val ids = HashMap<Int, Int>()
    @Synchronized
    fun of(key: Int): Int = ids.getOrPut(key) { View.generateViewId() }
}
