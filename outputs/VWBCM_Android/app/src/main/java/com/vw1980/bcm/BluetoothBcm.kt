package com.vw1980.bcm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothBcm {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var sequence = 1
    private val receiveBuffer = ArrayList<Byte>(10)

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> = adapter?.bondedDevices
        ?.filter { it.name?.contains("BCM-VW1980", ignoreCase = true) == true }
        ?.sortedBy { it.name } ?: emptyList()

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            close()
            adapter?.cancelDiscovery()
            val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            socket = device.createRfcommSocketToServiceRecord(spp).also { it.connect() }
            input = socket!!.inputStream
            output = socket!!.outputStream
        }
    }

    suspend fun send(command: Int, target: Int = 0, value: Int = 0): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val packet = BcmProtocol.command(sequence++ and 0xFF, command, target, value)
            requireNotNull(output) { "BCM no conectado" }.apply { write(packet); flush() }
            Unit
        }
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
                if (receiveBuffer.isEmpty() && value != 0xA5) continue
                receiveBuffer.add(value.toByte())
                if (receiveBuffer.size == 10) {
                    val packet = receiveBuffer.toByteArray()
                    receiveBuffer.clear()
                    if (BcmProtocol.isValid(packet)) packets += packet
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
        receiveBuffer.clear()
    }
}
