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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val bluetooth by lazy { BluetoothBcm(applicationContext) }
    private var ready by mutableStateOf(false)
    private var foreground by mutableStateOf(false)
    private fun hasPermissions() = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ready = hasPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.install(applicationContext)
        ready = hasPermissions()
        val permissions = mutableListOf<String>()
        if (!ready) permissions.addAll(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
        setContent { BcmApp(bluetooth, ready && foreground) }
    }

    override fun onResume() { super.onResume(); ready = hasPermissions(); foreground = true }
    override fun onStop() {
        foreground = false
        BcmLink.stopShowRequested = true // keep link, but never keep Autoshow unattended
        super.onStop()
    }
}

private enum class DetailGroup { WHITE, ORANGE }
private class PendingReply(val command: Int, val result: CompletableDeferred<Int>, var sequence: Int = -1)

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
    var reconnectEnabled by remember { mutableStateOf(savedAddress != null && !bluetooth.isPaused()) }
    var lastVerifiedAt by remember { mutableLongStateOf(0L) }
    var bcmState by remember { mutableStateOf<BcmProtocol.State?>(null) }
    var feedbackConfig by remember { mutableStateOf(BcmProtocol.Config()) }
    var authorizedDevices by remember { mutableStateOf<BcmProtocol.Devices?>(null) }
    var detailGroup by remember { mutableStateOf<DetailGroup?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Selecciona el BCM emparejado") }
    var showEditor by remember { mutableStateOf(false) }
    var showProgram by remember { mutableStateOf(ShowProgram()) }
    var showStatus by remember { mutableStateOf(ShowStatus()) }
    var showSupported by remember { mutableStateOf(false) }
    var programReady by remember { mutableStateOf(false) }
    var showBusy by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf("Carga el patrón desde el ESP32") }
    val receivedMasks = remember { IntArray(16) }
    val receivedTimes = remember { IntArray(16) { 500 } }
    var maskBits by remember { mutableIntStateOf(0) }
    var timeBits by remember { mutableIntStateOf(0) }
    var showRepeat by remember { mutableStateOf(true) }
    var pendingAck by remember { mutableStateOf<PendingReply?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var advancedTab by remember { mutableIntStateOf(0) }
    val confirmationLock = remember { Mutex() }
    var dashboard by remember { mutableStateOf(preferences.tiles) }
    var ldrConfig by remember { mutableStateOf<LdrConfig?>(null) }
    var ldrAdc by remember { mutableStateOf<Int?>(null) }
    var ldrBusy by remember { mutableStateOf(false) }
    var ldrNotice by remember { mutableStateOf("") }
    val ldrValues = remember { IntArray(4) }
    var ldrBits by remember { mutableIntStateOf(0) }
    val crash = remember { AppDiagnostics.lastCrash(context) }

    suspend fun confirmed(command: Int, target: Int = 0, value: Int = 0) {
      confirmationLock.withLock {
        val ack = CompletableDeferred<Int>()
        val pending = PendingReply(command, ack)
        pendingAck = pending
        try {
            pending.sequence = bluetooth.sendPacket(command, target, value).getOrThrow()
            check(withTimeout(4000) { ack.await() } == 0) { "El ESP32 rechazó el cambio" }
        } finally { pendingAck = null }
      }
    }

    fun saveProgram(program: ShowProgram) {
        scope.launch {
            showBusy = true
            try {
                confirmed(BcmProtocol.CMD_SHOW_BEGIN)
                program.steps.forEachIndexed { index, step ->
                    showNotice = "Enviando paso ${index + 1}/16…"
                    confirmed(BcmProtocol.CMD_SHOW_MASK, index, step.mask)
                    confirmed(BcmProtocol.CMD_SHOW_TIME, index, step.duration)
                }
                confirmed(BcmProtocol.CMD_SHOW_SAVE, value = if (program.repeat) 1 else 0)
                showProgram = program
                showNotice = "Patrón guardado y confirmado por el ESP32"
            } catch (error: Exception) {
                showNotice = "No se confirmó el guardado. Reintenta: ${error.message}"
            } finally { showBusy = false }
        }
    }

    LaunchedEffect(ready, savedAddress) {
        if (!ready) { connected = false; bcmState = null; return@LaunchedEffect }
        while (isActive) {
            devices = bluetooth.pairedDevices()
            if (savedAddress != null && selected == null) {
                selected = bluetooth.savedDevice(savedAddress!!)
                reconnectEnabled = !bluetooth.isPaused()
                if (selected == null) message = "Vuelve a emparejar tu auto desde Bluetooth"
            }
            delay(2500)
        }
    }

    fun send(command: Int, target: Int = BcmProtocol.TARGET_SYSTEM, value: Int = 0) {
        scope.launch {
            bluetooth.send(command, target, value).onFailure {
                connected = false
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
            BcmProtocol.TARGET_CFG_LOCK_COUNT -> feedbackConfig.copy(lockCount = value)
            BcmProtocol.TARGET_CFG_LOCK_GAP -> feedbackConfig.copy(lockGapMs = value)
            else -> feedbackConfig
        }
    }

    // Heartbeat: el ESP32 responde CMD_GET_STATE con su máscara real de salidas.
    LaunchedEffect(selected, reconnectEnabled, ready) {
        if (!ready) return@LaunchedEffect
        val device = selected ?: return@LaunchedEffect
        var lastPoll = 0L
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
                ldrConfig = null; ldrAdc = null; ldrBits = 0
                showSupported = false; programReady = false; maskBits = 0; timeBits = 0
                val connection = bluetooth.connect(device)
                if (connection.isSuccess) {
                    connected = true
                    lastVerifiedAt = SystemClock.elapsedRealtime()
                    message = "Verificando tu auto…"
                    // The service keeps verified state/configuration while the screen is off.
                } else {
                    message = "Buscando tu auto…"
                }
                if (connection.isFailure) delay(2000)
                continue
            }

            if (SystemClock.elapsedRealtime() - lastPoll >= if (showEditor) 200 else 1000) {
              lastPoll = SystemClock.elapsedRealtime()
              bluetooth.send(BcmProtocol.CMD_GET_STATE).onFailure {
                connected = false
                message = "BCM desconectado; reconectando…"
              }
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
                if (config != null) feedbackConfig = config.copy(lockCount = feedbackConfig.lockCount, lockGapMs = feedbackConfig.lockGapMs)
                packets.forEach { packet ->
                    val kind = packet[2].toInt() and 255
                    val cmd = packet[4].toInt() and 255
                    val target = packet[5].toInt() and 255
                    val value = (packet[6].toInt() and 255) or ((packet[7].toInt() and 255) shl 8)
                    if (kind == 0x82 && cmd == 0x81 && pendingAck?.command == target &&
                        pendingAck?.sequence == (packet[3].toInt() and 255)) pendingAck?.result?.complete(value)
                    if (kind == 0x81) when (cmd) {
                        0x85 -> {
                            showSupported = true
                            showStatus = ShowStatus(value == 1, target.coerceIn(0, 15))
                            showRepeat = packet[8].toInt() != 0
                        }
                        0x86 -> if (target < 16 && value <= 127) {
                            receivedMasks[target] = value; maskBits = maskBits or (1 shl target)
                        }
                        0x87 -> if (target < 16 && value in 200..5000) {
                            receivedTimes[target] = value; timeBits = timeBits or (1 shl target)
                        }
                        0x88 -> feedbackConfig = feedbackConfig.copy(lockCount = target.coerceIn(1, 5), lockGapMs = value.coerceIn(50, 2000))
                        0x89 -> if (value in 0..4095) ldrAdc = value
                        0x8A -> if (target < 4) { ldrValues[target] = value; ldrBits = ldrBits or (1 shl target) }
                    }
                }
                if (ldrBits == 15) {
                    val parsed = LdrConfig(ldrValues[0], ldrValues[1], ldrValues[2], ldrValues[3] == 1)
                    if (parsed.valid()) ldrConfig = parsed
                    ldrBits = 0
                }
                if (maskBits == 65535 && timeBits == 65535) {
                    showProgram = ShowProgram(List(16) { ShowStep(receivedMasks[it], receivedTimes[it]) }, showRepeat)
                    programReady = true; maskBits = 0; timeBits = 0
                    showNotice = "Patrón leído del ESP32"
                }
            }.onFailure {
                connected = false
                message = "BCM desconectado; reconectando…"
            }

            if (connected && SystemClock.elapsedRealtime() - lastVerifiedAt > 5000) {
                connected = false
                bcmState = null
                message = "Sin respuesta del BCM; reconectando…"
            }
            delay(100)
        }
    }

    val state = if (connected && ready) bcmState else null
    val controlsEnabled = connected && ready && state != null
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFDCDCDC), onPrimary = Color(0xFF060606),
        background = Color(0xFF060606), surface = Color(0xFF101010),
        surfaceVariant = Color(0xFF171717), onSurfaceVariant = Color(0xFFBDBDBD),
        primaryContainer = Color(0xFF303030), onPrimaryContainer = Color.White,
        secondaryContainer = Color(0xFF202020), onSecondaryContainer = Color(0xFFDCDCDC)
    ) else lightColorScheme(
        primary = Color(0xFF060606), onPrimary = Color.White,
        background = Color(0xFFFAF9F6), surface = Color.White,
        surfaceVariant = Color(0xFFEEEDE9), onSurfaceVariant = Color(0xFF454545),
        primaryContainer = Color(0xFFDCDCDC), onPrimaryContainer = Color(0xFF060606),
        secondaryContainer = Color(0xFFEEEDE9), onSecondaryContainer = Color(0xFF060606)
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
                    Box(Modifier.weight(1f).height(66.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF060606))) {
                        Image(painterResource(R.drawable.syntx_logo), "SYNTX", contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.45f, scaleY = 1.45f))
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
                        Text(if (connected) "Conectado" else "Conectar ${bluetooth.label(selected!!)}")
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

                Dashboard(dashboard, state, controlsEnabled, if (controlsEnabled) ldrAdc else null,
                    onAction = { action ->
                        when (action) {
                            TileAction.PARKING -> send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_PARKING, if (state?.parking == true) 0 else 1)
                            TileAction.LOW -> send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.lowBeam == true) 0 else 1)
                            TileAction.HIGH -> send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, if (state?.highBeam == true) 0 else 2)
                            TileAction.WHITE -> send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_WHITE_RINGS, if (state?.whiteLeft == true && state?.whiteRight == true) 0 else 1)
                            TileAction.ORANGE -> send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_ORANGE_RINGS, if (state?.orangeLeft == true && state?.orangeRight == true) 0 else 1)
                            TileAction.LOCK -> send(BcmProtocol.CMD_LOCK)
                            TileAction.UNLOCK -> send(BcmProtocol.CMD_UNLOCK)
                            TileAction.HORN -> send(BcmProtocol.CMD_PULSE_HORN, value = 300)
                            TileAction.LDR -> { advancedTab = 0; showAdvanced = true }
                            TileAction.SHOW -> showEditor = true
                        }
                    }, onDetails = { action ->
                        when (action) {
                            TileAction.WHITE -> detailGroup = DetailGroup.WHITE
                            TileAction.ORANGE -> detailGroup = DetailGroup.ORANGE
                            else -> { advancedTab = 1; showAdvanced = true }
                        }
                    }, onEdit = { advancedTab = 1; showAdvanced = true })

                OutlinedButton(onClick = {
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_HEADLIGHT, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_PARKING, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_WHITE_RINGS, 0)
                    send(BcmProtocol.CMD_SET_MANUAL, BcmProtocol.TARGET_ORANGE_RINGS, 0)
                }, enabled = controlsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PowerSettingsNew, null); Spacer(Modifier.width(7.dp)); Text("Apagar iluminación")
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
                onAdvanced = { showSettings = false; advancedTab = 0; showAdvanced = true },
                onTestChirp = { send(BcmProtocol.CMD_TEST_LOCK_SOUND) },
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
        if (showAdvanced) {
            AdvancedSettings(ldrConfig, if (controlsEnabled) ldrAdc else null, controlsEnabled && !showBusy,
                ldrBusy, ldrNotice, dashboard,
                onTiles = { dashboard = it; preferences.tiles = it },
                onSave = { config ->
                    ldrBusy = true
                    scope.launch {
                        try {
                            listOf(config.on, config.off, config.delayMs, if (config.darkLow) 1 else 0).forEachIndexed { index, value ->
                                confirmed(BcmProtocol.CMD_LDR_STAGE, index, value)
                            }
                            confirmed(BcmProtocol.CMD_LDR_SAVE)
                            ldrConfig = config
                            ldrNotice = "Calibración guardada y confirmada por el ESP32"
                        } catch (error: Exception) { ldrNotice = "No se confirmó el cambio: ${error.message}" }
                        finally { ldrBusy = false }
                    }
                }, onReload = { ldrBits = 0; send(BcmProtocol.CMD_GET_CONFIG) },
                onDismiss = { showAdvanced = false }, crash = crash, initialTab = advancedTab,
                onCopyCrash = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Diagnóstico SYNTX", crash ?: ""))
                })
        }
        if (showEditor) {
            AutoshowEditor(showProgram, showStatus, controlsEnabled && showSupported, showBusy, programReady,
                if (!showSupported) "Actualiza el firmware principal para usar Autoshow" else showNotice,
                onDismiss = { send(BcmProtocol.CMD_SHOW_CONTROL, value = 0); showEditor = false },
                onSave = { saveProgram(it) },
                onReload = {
                    maskBits = 0; timeBits = 0; programReady = false
                    send(BcmProtocol.CMD_SHOW_GET)
                    showNotice = "Leyendo patrón del ESP32…"
                },
                onControl = { run -> send(BcmProtocol.CMD_SHOW_CONTROL, value = if (run) 1 else 0) },
                onPrepare = {
                    scope.launch {
                        showBusy = true
                        try {
                            confirmed(BcmProtocol.CMD_SHOW_CONTROL, value = 0)
                            listOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11).forEach {
                                confirmed(BcmProtocol.CMD_RELEASE_MANUAL, it)
                            }
                            confirmed(BcmProtocol.CMD_SET_AUTO, value = 0)
                            showNotice = "Mandos liberados. El automático queda desactivado hasta que lo actives de nuevo."
                        } catch (error: Exception) { showNotice = "No se completó: ${error.message}" }
                        finally { showBusy = false }
                    }
                })
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
                            Text("${bluetooth.label(device)}\n${device.address}")
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
                                  onTestChirp: () -> Unit,
                                  onAdvanced: () -> Unit,
                                  onDismiss: () -> Unit, onSet: (Int, Int) -> Unit) {
    var duration by remember(config.feedbackMs) { mutableFloatStateOf(config.feedbackMs.toFloat()) }
    var count by remember(config.lockCount) { mutableFloatStateOf(config.lockCount.toFloat()) }
    var gap by remember(config.lockGapMs) { mutableFloatStateOf(config.lockGapMs.toFloat()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdvanced, modifier = Modifier.fillMaxWidth()) { Text("Ajustes avanzados · LDR y tablero") }
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
            Text("Pitidos al cerrar: ${count.roundToInt()}")
            Slider(value = count, onValueChange = { count = it }, valueRange = 1f..5f, steps = 3,
                enabled = enabled, onValueChangeFinished = { onSet(BcmProtocol.TARGET_CFG_LOCK_COUNT, count.roundToInt()) })
            Text("Pausa entre pitidos: ${gap.roundToInt()} ms")
            Slider(value = gap, onValueChange = { gap = it }, valueRange = 50f..2000f,
                enabled = enabled, onValueChangeFinished = { onSet(BcmProtocol.TARGET_CFG_LOCK_GAP, gap.roundToInt()) })
            OutlinedButton(enabled = enabled, onClick = onTestChirp) { Text("Probar aviso sin mover seguros") }
            Text("Al abrir", style = MaterialTheme.typography.titleMedium)
            SettingsSwitch("Chirrido de claxon", config.unlockChirp, enabled) { onSet(BcmProtocol.TARGET_CFG_UNLOCK_CHIRP, if (it) 1 else 0) }
            SettingsSwitch("Encender aros blancos", config.unlockWhite, enabled) { onSet(BcmProtocol.TARGET_CFG_UNLOCK_WHITE, if (it) 1 else 0) }
            Text("Duración del aviso: ${duration.roundToInt()} ms", style = MaterialTheme.typography.titleMedium)
            Slider(value = duration, onValueChange = { duration = it }, onValueChangeFinished = {
                onSet(BcmProtocol.TARGET_CFG_FEEDBACK_MS, duration.roundToInt())
            }, valueRange = 50f..1000f, enabled = enabled)
            Text("Cada pitido dura como máximo 1 segundo. La duración también se usa para el aviso al abrir y el destello blanco.", style = MaterialTheme.typography.bodySmall,
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
