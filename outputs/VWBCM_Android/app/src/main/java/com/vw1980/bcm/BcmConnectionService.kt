package com.vw1980.bcm

import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

// One socket and one reader per process, independent of Activity recreation.
internal object BcmLink {
    val transport = BluetoothTransport()
    var desired: BluetoothDevice? = null
    var connected = false
    var serviceRunning = false
    var paused = false
    var stopShowRequested = false
    var lastStateAt = 0L
    val packets = ArrayDeque<ByteArray>()
    val snapshot = linkedMapOf<Int, ByteArray>()

    fun disconnect() {
        connected = false
        lastStateAt = 0
        transport.close()
        packets.clear()
        snapshot.clear()
    }

    fun receive(packet: ByteArray) {
        if (BcmProtocol.parseState(packet) != null) lastStateAt = SystemClock.elapsedRealtime()
        if ((packet[2].toInt() and 255) == 0x81) {
            snapshot[((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)] = packet
        }
        if (packets.size >= 256) packets.removeFirst()
        packets.addLast(packet)
    }
}

// UI facade: only the service consumes the transport. No commands are replayed.
class BluetoothBcm(context: Context) {
    private val context = context.applicationContext
    private var replay = emptyList<ByteArray>()
    fun label(device: BluetoothDevice) = BcmLink.transport.label(device)
    fun pairedDevices() = BcmLink.transport.pairedDevices()
    fun savedDevice(address: String) = BcmLink.transport.savedDevice(address)
    fun isEnabled() = BcmLink.transport.isEnabled()
    fun isPaused() = BcmLink.paused

    suspend fun connect(device: BluetoothDevice): Result<Unit> = try {
        // Let a previous explicit stop finish before starting a new service.
        withTimeout(3000) {
            while (BcmLink.desired == null && BcmLink.serviceRunning) delay(50)
        }
        BcmLink.paused = false
        if (BcmLink.desired?.address != device.address) {
            BcmLink.disconnect()
            BcmLink.desired = device
        }
        if (!BcmLink.serviceRunning) {
            ContextCompat.startForegroundService(context, Intent(context, BcmConnectionService::class.java))
        }
        withTimeout(12000) {
            while (!BcmLink.connected || BcmLink.lastStateAt == 0L) delay(50)
        }
        replay = BcmLink.snapshot.values.toList()
        Result.success(Unit)
    } catch (error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        Result.failure(error)
    }

    suspend fun send(command: Int, target: Int = 0, value: Int = 0): Result<Unit> =
        sendPacket(command, target, value).map { Unit }

    suspend fun sendPacket(command: Int, target: Int = 0, value: Int = 0): Result<Int> {
        if (!BcmLink.connected) return Result.failure(IllegalStateException("BCM desconectado"))
        return BcmLink.transport.sendPacket(command, target, value)
    }

    suspend fun readIncomingPackets(): Result<List<ByteArray>> {
        if (!BcmLink.connected) return Result.failure(IllegalStateException("BCM desconectado"))
        val result = replay + BcmLink.packets.toList()
        replay = emptyList()
        BcmLink.packets.clear()
        return Result.success(result)
    }

    // Explicit user action only: hand off to another phone or forget vehicle.
    fun close() {
        BcmLink.paused = true
        BcmLink.desired = null
        BcmLink.disconnect()
        replay = emptyList()
        context.stopService(Intent(context, BcmConnectionService::class.java))
    }
}

class BcmConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bcm_connection", "Conexión al auto", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "bcm_connection")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SYNTX · conexión al auto")
            .setContentText("Bluetooth activo. Usa Ceder conexión en ajustes para desconectar.")
            .setContentIntent(open).setOngoing(true).build()
        try { startForeground(1980, notification) }
        catch (_: SecurityException) { stopSelf(); return }
        BcmLink.serviceRunning = true
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SYNTX:Bluetooth")
            .apply { setReferenceCounted(false) }
        scope.launch {
            var lastPoll = 0L
            while (isActive) {
                val device = BcmLink.desired ?: break
                try {
                    if (!BcmLink.transport.isEnabled()) {
                        BcmLink.disconnect()
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                        delay(2500); continue
                    }
                    // Bounded lease: no indefinite lock if the job fails.
                    wakeLock?.acquire(15000)
                    if (!BcmLink.connected) {
                        BcmLink.transport.connect(device).getOrThrow()
                        if (BcmLink.desired?.address != device.address) { BcmLink.disconnect(); continue }
                        BcmLink.connected = true
                        BcmLink.lastStateAt = 0L
                        BcmLink.transport.send(BcmProtocol.CMD_GET_STATE).getOrThrow()
                        BcmLink.transport.send(BcmProtocol.CMD_GET_CONFIG).getOrThrow()
                        BcmLink.transport.send(BcmProtocol.CMD_SHOW_GET).getOrThrow()
                        lastPoll = SystemClock.elapsedRealtime()
                    }
                    if (SystemClock.elapsedRealtime() - lastPoll >= 1000) {
                        BcmLink.transport.send(BcmProtocol.CMD_GET_STATE).getOrThrow()
                        lastPoll = SystemClock.elapsedRealtime()
                    }
                    if (BcmLink.stopShowRequested) {
                        BcmLink.transport.send(BcmProtocol.CMD_SHOW_CONTROL, value = 0).getOrThrow()
                        BcmLink.stopShowRequested = false
                    }
                    BcmLink.transport.readIncomingPackets().getOrThrow().forEach(BcmLink::receive)
                    // Initial verification also has a deadline, independent of UI.
                    if (BcmLink.lastStateAt == 0L) {
                        withTimeout(5000) {
                            while (BcmLink.lastStateAt == 0L) {
                                delay(100)
                                if (SystemClock.elapsedRealtime() - lastPoll >= 1000) {
                                    BcmLink.transport.send(BcmProtocol.CMD_GET_STATE).getOrThrow()
                                    lastPoll = SystemClock.elapsedRealtime()
                                }
                                BcmLink.transport.readIncomingPackets().getOrThrow().forEach(BcmLink::receive)
                            }
                        }
                        BcmLink.transport.send(BcmProtocol.CMD_GET_CONFIG).getOrThrow()
                        BcmLink.transport.send(BcmProtocol.CMD_SHOW_GET).getOrThrow()
                    }
                    check(SystemClock.elapsedRealtime() - BcmLink.lastStateAt < 5000) { "Sin heartbeat" }
                    delay(100)
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    BcmLink.disconnect()
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    delay(2500)
                }
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        BcmLink.serviceRunning = false
        BcmLink.disconnect()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }
}
