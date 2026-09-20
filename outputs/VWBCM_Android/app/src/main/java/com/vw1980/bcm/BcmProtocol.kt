package com.vw1980.bcm

object BcmProtocol {
    const val CMD_SET_AUTO = 0x10
    const val CMD_SET_MANUAL = 0x11
    const val CMD_RELEASE_MANUAL = 0x12
    const val CMD_PULSE_HORN = 0x13
    const val CMD_LOCK = 0x14
    const val CMD_UNLOCK = 0x15
    const val CMD_GET_STATE = 0x18
    const val CMD_SET_CONFIG = 0x19
    const val CMD_GET_CONFIG = 0x1A
    const val CMD_AUTHORIZE_DEVICE = 0x1B

    data class Devices(val count: Int, val enrollmentSeconds: Int)
    fun parseDevices(packet: ByteArray): Devices? {
        if (!isValid(packet) || packet[2].toInt() and 0xFF != 0x81 || packet[4].toInt() and 0xFF != 0x84) return null
        return Devices(packet[6].toInt() and 0xFF, packet[8].toInt() and 0xFF)
    }

    const val TARGET_SYSTEM = 0
    const val TARGET_HEADLIGHT = 1
    const val TARGET_PARKING = 2
    const val TARGET_WHITE_RINGS = 3
    const val TARGET_ORANGE_LEFT = 4
    const val TARGET_ORANGE_RIGHT = 5
    const val TARGET_WHITE_LEFT = 9
    const val TARGET_WHITE_RIGHT = 10
    const val TARGET_ORANGE_RINGS = 11
    const val TARGET_CFG_LOCK_CHIRP = 20
    const val TARGET_CFG_LOCK_WHITE = 21
    const val TARGET_CFG_UNLOCK_CHIRP = 22
    const val TARGET_CFG_UNLOCK_WHITE = 23
    const val TARGET_CFG_FEEDBACK_MS = 24

    data class State(val outputMask: Int, val originalInputMask: Int) {
        val lowBeam get() = outputMask and (1 shl 0) != 0
        val highBeam get() = outputMask and (1 shl 1) != 0
        val parking get() = outputMask and (1 shl 2) != 0
        val whiteLeft get() = outputMask and (1 shl 3) != 0
        val whiteRight get() = outputMask and (1 shl 4) != 0
        val orangeLeft get() = outputMask and (1 shl 5) != 0
        val orangeRight get() = outputMask and (1 shl 6) != 0
        val automatic get() = outputMask and (1 shl 12) != 0
        val dark get() = outputMask and (1 shl 13) != 0
    }

    data class Config(
        val lockChirp: Boolean = true,
        val lockWhite: Boolean = true,
        val unlockChirp: Boolean = false,
        val unlockWhite: Boolean = false,
        val feedbackMs: Int = 220
    )

    fun command(sequence: Int, command: Int, target: Int, value: Int): ByteArray {
        val packet = ByteArray(10)
        packet[0] = 0xA5.toByte()
        packet[1] = 0x01
        packet[2] = 0x01
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = command.toByte()
        packet[5] = target.toByte()
        packet[6] = (value and 0xFF).toByte()
        packet[7] = ((value shr 8) and 0xFF).toByte()
        packet[8] = 0
        packet[9] = crc8(packet, 9).toByte()
        return packet
    }

    fun isValid(packet: ByteArray): Boolean =
        packet.size == 10 &&
            (packet[0].toInt() and 0xFF) == 0xA5 &&
            (packet[1].toInt() and 0xFF) == 0x01 &&
            (packet[9].toInt() and 0xFF) == crc8(packet, 9)

    fun parseState(packet: ByteArray): State? {
        if (!isValid(packet) || (packet[2].toInt() and 0xFF) != 0x81 ||
            (packet[4].toInt() and 0xFF) != 0x80) return null
        val outputMask = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        return State(outputMask, packet[8].toInt() and 0xFF)
    }

    fun parseConfig(packet: ByteArray): Config? {
        if (!isValid(packet) || (packet[2].toInt() and 0xFF) != 0x81 ||
            (packet[4].toInt() and 0xFF) != 0x83) return null
        val flags = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        return Config(
            lockChirp = flags and 1 != 0,
            lockWhite = flags and (1 shl 1) != 0,
            unlockChirp = flags and (1 shl 2) != 0,
            unlockWhite = flags and (1 shl 3) != 0,
            feedbackMs = ((packet[8].toInt() and 0xFF) * 10).coerceIn(50, 1000)
        )
    }

    private fun crc8(data: ByteArray, length: Int): Int {
        var crc = 0
        repeat(length) { index ->
            crc = crc xor (data[index].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }
}
