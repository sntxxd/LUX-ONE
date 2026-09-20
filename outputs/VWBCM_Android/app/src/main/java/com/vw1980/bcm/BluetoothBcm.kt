package com.vw1980.bcm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BluetoothBcm {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var sequence = 1
    private val sendMutex = Mutex()
    private val receiveBuffer = ArrayList<Byte>(10)

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> = runCatching { adapter?.bondedDevices
        ?.filter { it.name?.contains("BCM-VW1980", ignoreCase = true) == true }
        ?.sortedBy { it.name } ?: emptyList() }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    fun savedDevice(address: String): BluetoothDevice? = runCatching {
        adapter?.bondedDevices?.firstOrNull { it.address == address }
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun isEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            close()
            adapter?.cancelDiscovery()
            val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            socket = device.createRfcommSocketToServiceRecord(spp)
            val pending = socket!!
            withTimeout(10000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { runCatching { pending.close() } }
                    thread(name = "LUX-Bluetooth-connect", isDaemon = true) {
                        try {
                            pending.connect()
                            continuation.resume(Unit)
                        } catch (error: Exception) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
            input = socket!!.inputStream
            output = socket!!.outputStream
        }.onFailure { close(); if (it is CancellationException && it !is TimeoutCancellationException) throw it }
    }

    suspend fun send(command: Int, target: Int = 0, value: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        sendMutex.withLock { runCatching {
            val packet = BcmProtocol.command(sequence++ and 0xFF, command, target, value)
            requireNotNull(output) { "BCM no conectado" }.apply { write(packet); flush() }
            Unit
        } }
    }

    // Lee sin bloquear los ACK/STATE que ya llegaron. CMD_GET_STATE responde con
    // una trama STATE de 10 bytes que sirve como heartbeat y estado de interfaz.
    suspend fun readIncomingPackets(): Result<List<ByteArray>> = withContext(Dispatchers.IO) {
        runCatching {
            val source = requireNotNull(input) { "BCM no conectado" }
            val packets = mutableListOf<ByteArray>()
            while (source.available() > 0) {
                val value = source.read()
                if (value < 0) error("Bluetooth desconectado")
                synchronized(receiveBuffer) {
                    if (receiveBuffer.isNotEmpty() || value == 0xA5) {
                        receiveBuffer.add(value.toByte())
                        if (receiveBuffer.size == 10) {
                            val packet = receiveBuffer.toByteArray()
                            if (BcmProtocol.isValid(packet)) {
                                packets += packet
                                receiveBuffer.clear()
                            } else {
                                receiveBuffer.removeAt(0)
                                while (receiveBuffer.isNotEmpty() && receiveBuffer[0] != 0xA5.toByte()) receiveBuffer.removeAt(0)
                            }
                        }
                    }
                }
            }
            packets
        }
    }

    fun close() {
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        synchronized(receiveBuffer) { receiveBuffer.clear() }
    }
}
