package com.vw1980.bcm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val bluetooth = BluetoothBcm()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        setContent { BcmApp(bluetooth) }
    }

    override fun onDestroy() { bluetooth.close(); super.onDestroy() }
}

private enum class DetailGroup { WHITE, ORANGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BcmApp(bluetooth: BluetoothBcm) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var connected by remember { mutableStateOf(false) }
    var reconnectEnabled by remember { mutableStateOf(false) }
    var lastVerifiedAt by remember { mutableLongStateOf(0L) }
    var bcmState by remember { mutableStateOf<BcmProtocol.State?>(null) }
    var feedbackConfig by remember { mutableStateOf(BcmProtocol.Config()) }
    var detailGroup by remember { mutableStateOf<DetailGroup?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Selecciona el BCM emparejado") }

    fun send(command: Int, target: Int = BcmProtocol.TARGET_SYSTEM, value: Int = 0) {
        scope.launch {
            bluetooth.send(command, target, value).onFailure {
                connected = false
                bluetooth.close()
                message = "BCM desconectado; reconectando…"
            }
        }
    }

    fun updateFeedbackConfig(target: Int, value: Int) {
        send(BcmProtocol.CMD_SET_CONFIG, target, value)
        feedbackConfig = when (target) {
            BcmProtocol.TARGET_CFG_LOCK_CHIRP -> feedbackConfig.copy(lockChirp = value == 1)
            BcmProtocol.TARGET_CFG_LOCK_WHITE -> feedbackConfig.copy(lockWhite = value == 1)
            BcmProtocol.TARGET_CFG_UNLOCK_CHIRP -> feedbackConfig.copy(unlockChirp = value == 1)
            BcmProtocol.TARGET_CFG_UNLOCK_WHITE -> feedbackConfig.copy(unlockWhite = value == 1)
            BcmProtocol.TARGET_CFG_FEEDBACK_MS -> feedbackConfig.copy(feedbackMs = value)
            else -> feedbackConfig
        }
    }

    // Heartbeat: el ESP32 responde CMD_GET_STATE con su máscara real de salidas.
    LaunchedEffect(selected, reconnectEnabled) {
        val device = selected ?: return@LaunchedEffect
        while (isActive && reconnectEnabled) {
            if (!connected) {
                val connection = bluetooth.connect(device)
                if (connection.isSuccess) {
                    connected = true
                    lastVerifiedAt = System.currentTimeMillis()
                    message = "Reconectado a ${device.name}"
                    bluetooth.send(BcmProtocol.CMD_GET_CONFIG)
                } else {
                    message = "Reconectando…"
                }
                delay(2000)
                continue
            }

            bluetooth.send(BcmProtocol.CMD_GET_STATE).onFailure {
                connected = false
                bluetooth.close()
                message = "BCM desconectado; reconectando…"
            }

            bluetooth.readIncomingPackets().onSuccess { packets ->
                val state = packets.mapNotNull { BcmProtocol.parseState(it) }.lastOrNull()
                val config = packets.mapNotNull { BcmProtocol.parseConfig(it) }.lastOrNull()
                if (state != null) {
                    bcmState = state
                    lastVerifiedAt = System.currentTimeMillis()
                    message = if (state.automatic) {
                        if (state.dark) "Automático · oscuro" else "Automático · claro"
                    } else "Control manual"
                }
                if (config != null) feedbackConfig = config
            }.onFailure {
                connected = false
                bluetooth.close()
                message = "BCM desconectado; reconectando…"
            }

            if (connected && System.currentTimeMillis() - lastVerifiedAt > 5000) {
                connected = false
                bluetooth.close()
                message = "Sin respuesta del BCM; reconectando…"
            }
            delay(1500)
        }
    }

    val state = bcmState
    val amber = Color(0xFFFFB000)
    val darkScheme = darkColorScheme(primary = amber, secondary = amber, surface = Color(0xFF1B1E23))
    MaterialTheme(colorScheme = darkScheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("VW 1980", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("BCM · Nodo principal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = {
                        devices = bluetooth.pairedDevices()
                        selected = devices.firstOrNull()
                        message = if (devices.isEmpty()) "Empareja BCM-VW1980-Main en Android" else "BCM encontrado"
                    }) { Icon(Icons.Outlined.Bluetooth, null); Spacer(Modifier.width(7.dp)); Text("Buscar") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Ajustes") }
                }

                if (selected != null) {
                    Button(onClick = {
                        reconnectEnabled = true
                        message = "Conectando…"
                    }, enabled = !connected, modifier = Modifier.fillMaxWidth()) {
                        Text(if (connected) "Conectado" else "Conectar ${selected!!.name}")
                    }
                }

                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (state?.automatic == true) "Automático" else "Control manual", style = MaterialTheme.typography.titleLarge)
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Modo automático")
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = state?.automatic == true, onCheckedChange = { enabled ->
                                if (enabled) {
                                    listOf(
                                        BcmProtocol.TARGET_HEADLIGHT, BcmProtocol.TARGET_PARKING,
                                        BcmProtocol.TARGET_WHITE_RINGS, BcmProtocol.TARGET_WHITE_LEFT,
                                        BcmProtocol.TARGET_WHITE_RIGHT
                                    ).forEach { send(BcmProtocol.CMD_RELEASE_MANUAL, it) }
                                }
                                send(BcmProtocol.CMD_SET_AUTO, BcmProtocol.TARGET_SYSTEM, if (enabled) 1 else 0)
                            }, enabled = connected)
                        }
                    }
                }

                Text("Iluminación", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LightButton("Cuartos", Icons.Outlined.Lightbulb, state?.parking == true, connected,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_PARKING, if (state?.parking == true) 0 else 1) })
                    LightButton("Bajas", Icons.Outlined.WbSunny, state?.lowBeam == true, connected,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.lowBeam == true) 0 else 1) })
                    LightButton("Altas", Icons.Outlined.Highlight, state?.highBeam == true, connected,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.highBeam == true) 0 else 2) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LightButton("Blancos", Icons.Outlined.Circle, state?.whiteLeft == true && state?.whiteRight == true, connected,
                        onClick = {
                            val enabled = !(state?.whiteLeft == true && state?.whiteRight == true)
                            send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_WHITE_RINGS, if (enabled) 1 else 0)
                        }, onLongClick = { detailGroup = DetailGroup.WHITE })
                    LightButton("Naranjas", Icons.Outlined.Circle, state?.orangeLeft == true && state?.orangeRight == true, connected,
                        onClick = {
                            val enabled = !(state?.orangeLeft == true && state?.orangeRight == true)
                            send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_ORANGE_RINGS, if (enabled) 1 else 0)
                        }, onLongClick = { detailGroup = DetailGroup.ORANGE })
                    Spacer(Modifier.weight(1f))
                }

                Text("Mantén pulsado Blancos o Naranjas para controlar izquierda y derecha.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedButton(onClick = {
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_PARKING, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_WHITE_RINGS, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_ORANGE_RINGS, 0)
                }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PowerSettingsNew, null); Spacer(Modifier.width(7.dp)); Text("Apagar iluminación")
                }

                Text("Vehículo", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Bloquear", Icons.Outlined.Lock, connected) { send(BcmProtocol.CMD_LOCK) }
                    ActionButton("Abrir", Icons.Outlined.LockOpen, connected) { send(BcmProtocol.CMD_UNLOCK) }
                    ActionButton("Claxon", Icons.Outlined.VolumeUp, connected) { send(BcmProtocol.CMD_PULSE_HORN, value = 300) }
                }
            }
        }

        detailGroup?.let { group ->
            LightDetailSheet(group, state, connected, onDismiss = { detailGroup = null }) { target, enabled ->
                send(BcmProtocol.CMD_SET_MANUAL, target, if (enabled) 1 else 0)
            }
        }
        if (showSettings) {
            FeedbackSettingsSheet(feedbackConfig, connected, onDismiss = { showSettings = false }) { target, value ->
                updateFeedbackConfig(target, value)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightDetailSheet(group: DetailGroup, state: BcmProtocol.State?, enabled: Boolean,
                             onDismiss: () -> Unit, onSet: (Int, Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (group == DetailGroup.WHITE) "Aros blancos" else "Aros naranjas", style = MaterialTheme.typography.titleLarge)
            Text("Control individual", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (group == DetailGroup.WHITE) {
                IndividualLight("Izquierdo", state?.whiteLeft == true, enabled) { onSet(BcmProtocol.TARGET_WHITE_LEFT, it) }
                IndividualLight("Derecho", state?.whiteRight == true, enabled) { onSet(BcmProtocol.TARGET_WHITE_RIGHT, it) }
            } else {
                IndividualLight("Izquierdo", state?.orangeLeft == true, enabled) { onSet(BcmProtocol.TARGET_ORANGE_LEFT, it) }
                IndividualLight("Derecho", state?.orangeRight == true, enabled) { onSet(BcmProtocol.TARGET_ORANGE_RIGHT, it) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun IndividualLight(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackSettingsSheet(config: BcmProtocol.Config, enabled: Boolean,
                                  onDismiss: () -> Unit, onSet: (Int, Int) -> Unit) {
    var duration by remember(config.feedbackMs) { mutableFloatStateOf(config.feedbackMs.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ajustes del vehículo", style = MaterialTheme.typography.titleLarge)
            Text("Estas preferencias se guardan dentro del ESP32.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Al cerrar", style = MaterialTheme.typography.titleMedium)
            SettingsSwitch("Chirrido de claxon", config.lockChirp, enabled) { onSet(BcmProtocol.TARGET_CFG_LOCK_CHIRP, if (it) 1 else 0) }
            SettingsSwitch("Encender aros blancos", config.lockWhite, enabled) { onSet(BcmProtocol.TARGET_CFG_LOCK_WHITE, if (it) 1 else 0) }
            Text("Al abrir", style = MaterialTheme.typography.titleMedium)
            SettingsSwitch("Chirrido de claxon", config.unlockChirp, enabled) { onSet(BcmProtocol.TARGET_CFG_UNLOCK_CHIRP, if (it) 1 else 0) }
            SettingsSwitch("Encender aros blancos", config.unlockWhite, enabled) { onSet(BcmProtocol.TARGET_CFG_UNLOCK_WHITE, if (it) 1 else 0) }
            Text("Duración del aviso: ${duration.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
            Slider(value = duration, onValueChange = { duration = it }, onValueChangeFinished = {
                onSet(BcmProtocol.TARGET_CFG_FEEDBACK_MS, duration.roundToInt())
            }, valueRange = 50f..1000f, enabled = enabled)
            Text("El claxon siempre queda limitado a 1 segundo por seguridad.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.LightButton(label: String, icon: ImageVector, active: Boolean, enabled: Boolean,
                                 onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.weight(1f).height(74.dp).combinedClickable(
            enabled = enabled, onClick = onClick, onLongClick = onLongClick
        )
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RowScope.ActionButton(label: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f).height(58.dp), contentPadding = PaddingValues(2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}
