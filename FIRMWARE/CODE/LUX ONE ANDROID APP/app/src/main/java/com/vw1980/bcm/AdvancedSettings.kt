package com.vw1980.bcm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

data class LdrConfig(val on: Int = 1500, val off: Int = 1900, val delayMs: Int = 5000, val darkLow: Boolean = true) {
    fun valid() = on in 0..4095 && off in 0..4095 && delayMs in 500..10000 &&
        if (darkLow) on + 50 <= off else off + 50 <= on
    companion object {
        fun calibrate(dark: Int, light: Int, delayMs: Int): LdrConfig? {
            if (dark !in 0..4095 || light !in 0..4095 || abs(light - dark) < 200) return null
            val span = light - dark
            return LdrConfig((dark + span * .35).roundToInt(), (dark + span * .65).roundToInt(), delayMs, dark < light)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettings(config: LdrConfig?, adc: Int?, enabled: Boolean, busy: Boolean, notice: String,
                     tiles: List<DashTile>, onTiles: (List<DashTile>) -> Unit,
                     onSave: (LdrConfig) -> Unit, onReload: () -> Unit, onDismiss: () -> Unit,
                     crash: String?, onCopyCrash: () -> Unit, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab) }
    var draft by remember(config) { mutableStateOf(config ?: LdrConfig()) }
    var darkSample by remember { mutableStateOf<Int?>(null) }
    var lightSample by remember { mutableStateOf<Int?>(null) }
    var calibrationNotice by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ajustes avanzados", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LDR", "Tablero", "Diagnóstico").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
                }
            }
            when (tab) {
                0 -> {
                    Text(adc?.let { "Lectura filtrada: $it / 4095" } ?: "Sin lectura del ESP32", style = MaterialTheme.typography.titleLarge)
                    Text("ADC relativo, no lux. La separación de umbrales evita parpadeos.")
                    if (config == null) Text("Conecta el firmware actualizado y pulsa Leer ajustes.")
                    TextButton(onClick = onReload, enabled = enabled && !busy) { Text("Leer ajustes del ESP32") }
                    NumberSetting("Encender", draft.on, 0..4095, enabled && config != null && !busy) { draft = draft.copy(on = it) }
                    NumberSetting("Apagar", draft.off, 0..4095, enabled && config != null && !busy) { draft = draft.copy(off = it) }
                    NumberSetting("Espera (ms)", draft.delayMs, 500..10000, enabled && config != null && !busy) { draft = draft.copy(delayMs = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("La lectura baja al oscurecer", Modifier.weight(1f))
                        Switch(checked = draft.darkLow, enabled = enabled && !busy, onCheckedChange = { draft = draft.copy(darkLow = it) })
                    }
                    Text("Calibración asistida", style = MaterialTheme.typography.titleMedium)
                    Text("Cubre el sensor, espera que la lectura se estabilice y captura oscuridad. Después ilumínalo y captura luz.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = enabled && adc != null, onClick = { darkSample = adc }) { Text("Oscuro: ${darkSample ?: "—"}") }
                        OutlinedButton(enabled = enabled && adc != null, onClick = { lightSample = adc }) { Text("Luz: ${lightSample ?: "—"}") }
                    }
                    TextButton(enabled = darkSample != null && lightSample != null && !busy, onClick = {
                        val calibrated = LdrConfig.calibrate(darkSample!!, lightSample!!, draft.delayMs)
                        if (calibrated == null) calibrationNotice = "Las muestras deben diferir al menos 200 puntos."
                        else { draft = calibrated; calibrationNotice = "Umbrales calculados. Pulsa Guardar para aplicarlos." }
                    }) { Text("Calcular sensibilidad") }
                    if (calibrationNotice.isNotEmpty()) Text(calibrationNotice)
                    if (!draft.valid()) Text("Separa los umbrales al menos 50 puntos y respeta el sentido del sensor.", color = MaterialTheme.colorScheme.error)
                    Button(enabled = enabled && config != null && draft.valid() && !busy, onClick = { onSave(draft) }) { Text(if (busy) "Guardando…" else "Guardar en ESP32") }
                    Text(notice)
                }
                1 -> {
                    Text("Tarjetas y botones", style = MaterialTheme.typography.titleLarge)
                    Text("Se guardan en este teléfono. Cambia nombres, acciones, tamaño, visibilidad y orden.")
                    tiles.forEachIndexed { index, tile ->
                        key(tile.id) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    fun replace(updated: DashTile) { onTiles(tiles.map { if (it.id == tile.id) updated else it }) }
                                    OutlinedTextField(value = tile.label, onValueChange = { replace(tile.copy(label = it.take(32))) }, label = { Text("Nombre") }, singleLine = true)
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { menu = true }) { Text("Acción: ${tile.action.label}") }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            TileAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { replace(tile.copy(action = action)); menu = false }) }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Visible", Modifier.weight(1f)); Switch(checked = tile.visible, onCheckedChange = { replace(tile.copy(visible = it)) })
                                        Text("Ancha"); Switch(checked = tile.wide, onCheckedChange = { replace(tile.copy(wide = it)) })
                                    }
                                    Row {
                                        TextButton(enabled = index > 0, onClick = { val list = tiles.toMutableList(); list.add(index - 1, list.removeAt(index)); onTiles(list) }) { Text("Subir") }
                                        TextButton(enabled = index < tiles.lastIndex, onClick = { val list = tiles.toMutableList(); list.add(index + 1, list.removeAt(index)); onTiles(list) }) { Text("Bajar") }
                                        TextButton(onClick = { onTiles(tiles.filterNot { it.id == tile.id }) }) { Text("Quitar") }
                                    }
                                }
                            }
                        }
                    }
                    Button(enabled = tiles.size < 20, onClick = { onTiles(tiles + DashTile(label = "Nueva tarjeta", action = TileAction.PARKING)) }) { Text("Añadir tarjeta") }
                    TextButton(onClick = { onTiles(DashboardStorage.defaults()) }) { Text("Restaurar tablero inicial") }
                    Text("Mantén pulsados los aros para controlar izquierda/derecha. No son widgets del escritorio de Android.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Último cierre inesperado", style = MaterialTheme.typography.titleLarge)
                    Text(crash ?: "No hay un fallo registrado por esta versión. Si vuelve a cerrarse, abre aquí y copia el diagnóstico.")
                    OutlinedButton(enabled = crash != null, onClick = onCopyCrash) { Text("Copiar diagnóstico") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Text("$label: $value")
    Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled)
}
