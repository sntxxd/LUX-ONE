package com.vw1980.bcm

import android.content.Context

enum class Appearance(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("lux_one", Context.MODE_PRIVATE)
    var appearance: Appearance
        get() = runCatching { Appearance.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)
        set(value) { prefs.edit().putString("theme", value.name).apply() }
    var vehicleAddress: String?
        get() = prefs.getString("vehicle_address", null)
        set(value) { prefs.edit().putString("vehicle_address", value).apply() }
}
