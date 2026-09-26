package com.vw1980.bcm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class ShowStep(val mask: Int = 0, val duration: Int = 500)
data class ShowProgram(val steps: List<ShowStep> = List(16) { ShowStep() }, val repeat: Boolean = true)
data class ShowStatus(val running: Boolean = false, val step: Int = 0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoshowEditor(program: ShowProgram, status: ShowStatus, enabled: Boolean, busy: Boolean, loaded: Boolean,
                   notice: String, onDismiss: () -> Unit, onSave: (ShowProgram) -> Unit,
                   onControl: (Boolean) -> Unit, onPrepare: () -> Unit, onReload: () -> Unit) {
    var draft by remember(program) { mutableStateOf(program) }
    var selected by remember { mutableIntStateOf(0) }
    var parked by remember { mutableStateOf(false) }
    val editable = enabled && loaded && !busy && !status.running
    val dirty = draft != program
    val tracks = listOf("Cuartos" to 2, "Bajas" to 0, "Altas" to 1,
        "Blanco I" to 3, "Blanco D" to 4, "Naranja I" to 5, "Naranja D" to 6)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AUTOSHOW / STUDIO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("16 pasos · ${if (status.running) "Reproduciendo ${status.step + 1}" else "Detenido"}")
            Text(notice, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = enabled && !busy && !status.running, onClick = onReload) { Text("Recargar patrón del ESP32 (descarta edición local)") }
            Text("Toca las celdas para dibujar el patrón. Desliza horizontalmente para ver los 16 pasos.", style = MaterialTheme.typography.bodySmall)
            Row {
                Column(Modifier.width(88.dp)) {
                    Spacer(Modifier.height(42.dp))
                    tracks.forEach { (label, _) -> Box(Modifier.height(46.dp), contentAlignment = Alignment.CenterStart) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    repeat(16) { index ->
                        val playhead = status.running && status.step == index
                        Column(Modifier.width(46.dp).background(if (playhead) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)) {
                            TextButton(onClick = { selected = index }, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(42.dp)) {
                                Text("${index + 1}", fontWeight = if (selected == index) FontWeight.Black else FontWeight.Normal)
                            }
                            tracks.forEach { (label, bit) ->
                                val active = draft.steps[index].mask and (1 shl bit) != 0
                                Surface(color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(7.dp),
                                    modifier = Modifier.size(46.dp).padding(3.dp)
                                        .semantics { contentDescription = "$label, paso ${index + 1}, ${if (active) "encendido" else "apagado"}" }
                                        .clickable(enabled = editable) {
                                            selected = index
                                            val steps = draft.steps.toMutableList()
                                            var mask = steps[index].mask xor (1 shl bit)
                                            // Altas y bajas no se energizan juntas.
                                            if (bit == 0 && mask and 1 != 0) mask = mask and 2.inv()
                                            if (bit == 1 && mask and 2 != 0) mask = mask and 1.inv()
                                            steps[index] = steps[index].copy(mask = mask)
                                            draft = draft.copy(steps = steps)
                                        }) { }
                            }
                        }
                    }
                }
            }
            Text("Paso ${selected + 1} · ${draft.steps[selected].duration} ms")
            Slider(value = draft.steps[selected].duration.toFloat(), valueRange = 200f..5000f,
                steps = 47, enabled = editable, onValueChange = { value ->
                    val steps = draft.steps.toMutableList()
                    steps[selected] = steps[selected].copy(duration = (value / 100).roundToInt() * 100)
                    draft = draft.copy(steps = steps)
                })
            TextButton(enabled = editable, onClick = { draft = draft.copy(steps = draft.steps.map { it.copy(duration = draft.steps[selected].duration) }) }) {
                Text("Aplicar este tiempo a todos")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repetir", Modifier.weight(1f))
                Switch(checked = draft.repeat, enabled = editable, onCheckedChange = { draft = draft.copy(repeat = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = editable, onClick = { draft = draft.copy(steps = draft.steps.map { it.copy(mask = 0) }) }) { Text("Vaciar") }
                Button(enabled = editable, onClick = { onSave(draft) }) { Text(if (busy) "Guardando…" else "Guardar en ESP32") }
            }
            Text("Prioridad: mandos originales → app → LDR → Autoshow. Los cuartos acompañan a bajas y altas.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = editable, onClick = onPrepare) { Text("Liberar mandos de app y desactivar automático") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = parked, onCheckedChange = { parked = it })
                Text("Confirmo que el auto está estacionado")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && loaded && !busy && parked && !dirty && !status.running, onClick = { onControl(true) }) { Text("Reproducir") }
                OutlinedButton(enabled = enabled, onClick = { onControl(false) }) { Text("Detener") }
            }
            Text("Se detiene al perder conexión o usar un mando original. Al salir del editor también se detiene. Guarda los cambios antes de reproducir.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
}
