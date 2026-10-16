package com.vw1980.bcm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TileAction(val label: String) {
    PARKING("Cuartos"), LOW("Bajas"), HIGH("Altas"), WHITE("Aros blancos"), ORANGE("Aros naranjas"),
    LOCK("Cerrar seguros"), UNLOCK("Abrir seguros"), HORN("Claxon"), LDR("Sensor de luz"), SHOW("Autoshow")
}
data class DashTile(val id: String = UUID.randomUUID().toString(), val label: String,
                    val action: TileAction, val visible: Boolean = true, val wide: Boolean = false)

object DashboardStorage {
    fun defaults() = TileAction.entries.map { DashTile(id = it.name, label = it.label, action = it, wide = it == TileAction.LDR) }
    fun encode(tiles: List<DashTile>) = JSONArray().apply {
        tiles.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("action", it.action.name)
            .put("visible", it.visible).put("wide", it.wide)) }
    }.toString()
    fun decode(json: String?): List<DashTile> = runCatching {
        if (json == null) return defaults()
        val array = JSONArray(json)
        (0 until minOf(array.length(), 20)).map { index ->
            val item = array.getJSONObject(index)
            val action = TileAction.valueOf(item.getString("action"))
            DashTile(item.getString("id"), item.optString("label", action.label).take(32), action,
                item.optBoolean("visible", true), item.optBoolean("wide", false))
        }.distinctBy { it.id }
    }.getOrElse { defaults() }
}

@Composable
fun Dashboard(tiles: List<DashTile>, state: BcmProtocol.State?, enabled: Boolean, adc: Int?,
              onAction: (TileAction) -> Unit, onDetails: (TileAction) -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Mi tablero", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onEdit) { Text("Editar") }
    }
    val shown = tiles.filter { it.visible }
    if (shown.isEmpty()) Text("Tu tablero está vacío. Toca Editar para añadir tarjetas.")
    var i = 0
    while (i < shown.size) {
        val first = shown[i]
        val second = if (!first.wide) shown.getOrNull(i + 1)?.takeUnless { it.wide } else null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DashboardTile(first, state, enabled, adc, Modifier.weight(1f), onAction, onDetails)
            if (second != null) DashboardTile(second, state, enabled, adc, Modifier.weight(1f), onAction, onDetails)
            else if (!first.wide) Spacer(Modifier.weight(1f))
        }
        i += if (second != null) 2 else 1
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardTile(tile: DashTile, state: BcmProtocol.State?, enabled: Boolean, adc: Int?, modifier: Modifier,
                          onAction: (TileAction) -> Unit, onDetails: (TileAction) -> Unit) {
    val active = when (tile.action) {
        TileAction.PARKING -> state?.parking == true
        TileAction.LOW -> state?.lowBeam == true
        TileAction.HIGH -> state?.highBeam == true
        TileAction.WHITE -> state?.whiteLeft == true || state?.whiteRight == true
        TileAction.ORANGE -> state?.orangeLeft == true || state?.orangeRight == true
        else -> false
    }
    val localAction = tile.action == TileAction.LDR || tile.action == TileAction.SHOW
    val detail = when (tile.action) {
        TileAction.LDR -> adc?.let { "$it / 4095 ADC" } ?: "Sin lectura"
        TileAction.SHOW -> "Studio · 16 pasos"
        TileAction.LOCK, TileAction.UNLOCK, TileAction.HORN -> if (enabled) "Pulsar para enviar" else "Sin conexión"
        else -> if (!enabled) "Sin conexión" else if (active) "● Activo" else "○ Apagado"
    }
    Surface(shape = RoundedCornerShape(24.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(112.dp).combinedClickable(enabled = enabled || localAction,
            onClick = { onAction(tile.action) }, onLongClick = { onDetails(tile.action) })) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(tile.label.ifBlank { tile.action.label }, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelMedium)
        }
    }
}
