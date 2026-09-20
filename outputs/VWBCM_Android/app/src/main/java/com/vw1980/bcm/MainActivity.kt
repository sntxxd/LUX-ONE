package com.vw1980.bcm

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.app.Activity
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val bluetooth = BluetoothBcm()
    private var ready by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private fun hasPermissions() = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ready = hasPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ready = hasPermissions()
        if (!ready) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
        setContent { BcmApp(bluetooth, ready && foreground) }
    }

    override fun onResume() { super.onResume(); ready = hasPermissions(); foreground = true }
    override fun onStop() { foreground = false; bluetooth.close(); super.onStop() }

    override fun onDestroy() { bluetooth.close(); super.onDestroy() }
}

private enum class DetailGroup { WHITE, ORANGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BcmApp(bluetooth: BluetoothBcm, ready: Boolean) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var appearance by remember { mutableStateOf(preferences.appearance) }
    var savedAddress by remember { mutableStateOf(preferences.vehicleAddress) }
    var showVehicles by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var selected by remember { mutableStateOf<BluetoothDevice?>(null) }
    var connected by remember { mutableStateOf(false) }
    var reconnectEnabled by remember { mutableStateOf(savedAddress != null) }
    var lastVerifiedAt by remember { mutableLongStateOf(0L) }
    var bcmState by remember { mutableStateOf<BcmProtocol.State?>(null) }
    var feedbackConfig by remember { mutableStateOf(BcmProtocol.Config()) }
    var authorizedDevices by remember { mutableStateOf<BcmProtocol.Devices?>(null) }
    var detailGroup by remember { mutableStateOf<DetailGroup?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Selecciona el BCM emparejado") }

    LaunchedEffect(ready, savedAddress) {
        if (!ready) { connected = false; bcmState = null; return@LaunchedEffect }
        while (isActive) {
            devices = bluetooth.pairedDevices()
            if (savedAddress != null && selected == null) {
                selected = bluetooth.savedDevice(savedAddress!!)
                reconnectEnabled = true
                if (selected == null) message = "Vuelve a emparejar tu auto desde Bluetooth"
            }
            delay(2500)
        }
    }

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
    LaunchedEffect(selected, reconnectEnabled, ready) {
        if (!ready) return@LaunchedEffect
        val device = selected ?: return@LaunchedEffect
        while (isActive && reconnectEnabled) {
            if (!bluetooth.isEnabled()) {
                connected = false
                bcmState = null
                message = "Activa Bluetooth para buscar tu auto"
                delay(2000)
                continue
            }
            if (!connected) {
                bcmState = null
                authorizedDevices = null
                val connection = bluetooth.connect(device)
                if (connection.isSuccess) {
                    connected = true
                    lastVerifiedAt = SystemClock.elapsedRealtime()
                    message = "Verificando tu auto…"
                    bluetooth.send(BcmProtocol.CMD_GET_CONFIG)
                } else {
                    message = "Buscando tu auto…"
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
                packets.mapNotNull { BcmProtocol.parseDevices(it) }.lastOrNull()?.let { authorizedDevices = it }
                if (state != null) {
                    bcmState = state
                    lastVerifiedAt = SystemClock.elapsedRealtime()
                    if (savedAddress != device.address) {
                        preferences.vehicleAddress = device.address
                        savedAddress = device.address
                    }
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

            if (connected && SystemClock.elapsedRealtime() - lastVerifiedAt > 5000) {
                connected = false
                bcmState = null
                bluetooth.close()
                message = "Sin respuesta del BCM; reconectando…"
            }
            delay(1500)
        }
    }

    val state = if (connected && ready) bcmState else null
    val controlsEnabled = connected && ready && state != null
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFF1F3F6), onPrimary = Color(0xFF15171B),
        background = Color(0xFF111317), surface = Color(0xFF1D2026),
        surfaceVariant = Color(0xFF292E36), onSurfaceVariant = Color(0xFFB8C1CE)
    ) else lightColorScheme(
        primary = Color(0xFF111317), onPrimary = Color.White,
        background = Color(0xFFF1F3F6), surface = Color.White,
        surfaceVariant = Color(0xFFE5E9EF), onSurfaceVariant = Color(0xFF536074)
    )
    SideEffect {
        val window = (context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = scheme.background.toArgb()
            window.navigationBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("LUX ONE", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text("TU AUTO, A TU MANERA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = {
                        devices = bluetooth.pairedDevices()
                        showVehicles = true
                    }) { Icon(Icons.Outlined.Bluetooth, "Mis autos") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "Ajustes") }
                }

                if (selected != null && !reconnectEnabled) {
                    Button(onClick = {
                        reconnectEnabled = true
                        message = "Conectando…"
                    }, enabled = !connected, modifier = Modifier.fillMaxWidth()) {
                        Text(if (connected) "Conectado" else "Conectar ${selected!!.name}")
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(30.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (savedAddress == null) "Bienvenido a tu garage" else "Tu clásico, conectado", style = MaterialTheme.typography.labelLarge)
                        Text("Volkswagen Sedán", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("1980  /  CONTROL INTELIGENTE", style = MaterialTheme.typography.labelSmall)
                        VehicleIllustration()
                        Text(if (controlsEnabled) "● Conectado" else "○ Sin conexión verificada", style = MaterialTheme.typography.labelLarge)
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
                            }, enabled = controlsEnabled)
                        }
                    }
                }

                Text("Iluminación", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LightButton("Cuartos", Icons.Outlined.Lightbulb, state?.parking == true, controlsEnabled,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_PARKING, if (state?.parking == true) 0 else 1) })
                    LightButton("Bajas", Icons.Outlined.WbSunny, state?.lowBeam == true, controlsEnabled,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.lowBeam == true) 0 else 1) })
                    LightButton("Altas", Icons.Outlined.Highlight, state?.highBeam == true, controlsEnabled,
                        onClick = { send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.highBeam == true) 0 else 2) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LightButton("Blancos", Icons.Outlined.Circle, state?.whiteLeft == true && state?.whiteRight == true, controlsEnabled,
                        onClick = {
                            val enabled = !(state?.whiteLeft == true && state?.whiteRight == true)
                            send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_WHITE_RINGS, if (enabled) 1 else 0)
                        }, onLongClick = { detailGroup = DetailGroup.WHITE })
                    LightButton("Naranjas", Icons.Outlined.Circle, state?.orangeLeft == true && state?.orangeRight == true, controlsEnabled,
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
                }, enabled = controlsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PowerSettingsNew, null); Spacer(Modifier.width(7.dp)); Text("Apagar iluminación")
                }

                Text("Vehículo", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("Bloquear", Icons.Outlined.Lock, controlsEnabled) { send(BcmProtocol.CMD_LOCK) }
                    ActionButton("Abrir", Icons.Outlined.LockOpen, controlsEnabled) { send(BcmProtocol.CMD_UNLOCK) }
                    ActionButton("Claxon", Icons.Outlined.VolumeUp, controlsEnabled) { send(BcmProtocol.CMD_PULSE_HORN, value = 300) }
                }
            }
        }

        detailGroup?.let { group ->
            LightDetailSheet(group, state, controlsEnabled, onDismiss = { detailGroup = null }) { target, enabled ->
                send(BcmProtocol.CMD_SET_MANUAL, target, if (enabled) 1 else 0)
            }
        }
        if (showSettings) {
            FeedbackSettingsSheet(feedbackConfig, controlsEnabled, appearance,
                authorizedDevices = authorizedDevices,
                onAuthorize = { send(BcmProtocol.CMD_AUTHORIZE_DEVICE) },
                onDisconnect = {
                    reconnectEnabled = false; connected = false; bcmState = null
                    bluetooth.close(); showSettings = false; message = "Conexión libre para otro dispositivo"
                },
                onAppearance = { appearance = it; preferences.appearance = it },
                onDismiss = { showSettings = false }) { target, value ->
                updateFeedbackConfig(target, value)
            }
        }
        if (showVehicles) {
            ModalBottomSheet(onDismissRequest = { showVehicles = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Tu garage", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Empareja el BCM en Android y selecciónalo. Al verificar la conexión, tu auto quedará registrado.")
                    devices.forEach { device ->
                        OutlinedButton(onClick = {
                            bluetooth.close(); connected = false; bcmState = null
                            selected = device; reconnectEnabled = true; showVehicles = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${device.name ?: "BCM"}\n${device.address}")
                        }
                    }
                    if (devices.isEmpty()) Text("No hay autos emparejados todavía.")
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Text("Abrir ajustes Bluetooth")
                    }
                    if (savedAddress != null) TextButton(onClick = {
                        reconnectEnabled = false; selected = null; connected = false; bcmState = null
                        bluetooth.close(); preferences.vehicleAddress = null; savedAddress = null
                    }) { Text("Olvidar auto en esta app") }
                    Text("La búsqueda automática funciona mientras la app está abierta. Olvidar el auto aquí no cambia los dispositivos autorizados del ESP32.",
                        style = MaterialTheme.typography.bodySmall)
                }
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
                                  appearance: Appearance, onAppearance: (Appearance) -> Unit,
                                  authorizedDevices: BcmProtocol.Devices?, onAuthorize: () -> Unit,
                                  onDisconnect: () -> Unit,
                                  onDismiss: () -> Unit, onSet: (Int, Int) -> Unit) {
    var duration by remember(config.feedbackMs) { mutableFloatStateOf(config.feedbackMs.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Apariencia", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { mode ->
                    FilterChip(selected = appearance == mode, onClick = { onAppearance(mode) }, label = { Text(mode.label) })
                }
            }
            HorizontalDivider()
            Text("Dispositivos autorizados", style = MaterialTheme.typography.titleLarge)
            Text(authorizedDevices?.let { "${it.count} de 8 dispositivos registrados" } ?: "Conecta el BCM actualizado para consultar dispositivos")
            Button(onClick = onAuthorize, enabled = enabled && authorizedDevices != null && authorizedDevices.count < 8) {
                Text("Añadir celular o tablet")
            }
            if (enabled && (authorizedDevices?.enrollmentSeconds ?: 0) > 0) {
                Text("Vinculación abierta: ${authorizedDevices!!.enrollmentSeconds} s. Empareja el nuevo dispositivo con el PIN del BCM y abre LUX ONE en él.")
            }
            Text("Se registra un dispositivo por ventana. Ambos quedan guardados; solo uno controla por Bluetooth a la vez.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDisconnect, enabled = enabled) { Text("Ceder conexión a otro dispositivo") }
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
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.weight(1f).height(116.dp).combinedClickable(
            enabled = enabled, onClick = onClick, onLongClick = onLongClick
        )
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(if (!enabled) "—" else if (active) "Encendido" else "Apagado", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RowScope.ActionButton(label: String, icon: ImageVector, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = Modifier.weight(1f).height(58.dp), contentPadding = PaddingValues(2.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}
