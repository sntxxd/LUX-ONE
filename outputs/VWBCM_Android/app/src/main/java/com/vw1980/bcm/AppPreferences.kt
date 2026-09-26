package com.vw1980.bcm

import android.content.Context

enum class Appearance(val label: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("lux_one", Context.MODE_PRIVATE)
    var appearance: Appearance
        get() = runCatching { Appearance.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)
        set(value) { prefs.edit().putString("theme", value.name).apply() }
    var vehicleAddress: String?
        get() = runCatching { prefs.getString("vehicle_address", null) }.getOrNull()
        set(value) { prefs.edit().putString("vehicle_address", value).apply() }
    var tiles: List<DashTile>
        get() = DashboardStorage.decode(runCatching { prefs.getString("dashboard", null) }.getOrNull())
        set(value) { prefs.edit().putString("dashboard", DashboardStorage.encode(value)).apply() }
}
