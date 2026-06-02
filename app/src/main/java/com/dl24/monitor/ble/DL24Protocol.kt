package com.dl24.monitor.ble

data class MeterReport(
    val deviceType: Int = 0,
    val deviceTypeName: String = "",
    val voltage: Double = 0.0,
    val current: Double = 0.0,
    val power: Double = 0.0,
    val energyWh: Double = 0.0,
    val energyAh: Double = 0.0,
    val price: Double = 0.0,
    val temperature: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 0,
    val backlight: Int = 0,
    val frequency: Double = 0.0,
    val powerFactor: Double = 0.0,
    val usbDMinus: Double = 0.0,
    val usbDPlus: Double = 0.0,
) {
    val durationStr: String
        get() = "%02d:%02d:%02d".format(hours, minutes, seconds)
}

data class ReplyMessage(
    val ok: Boolean = false,
    val unsupported: Boolean = false,
)

data class DeviceState(
    val outputOn: Boolean = false,
    val outputKnown: Boolean = false,
    val presetCurrentA: Double = 0.0,
    val presetCurrentKnown: Boolean = false,
    val presetCutoffV: Double = 0.0,
    val presetCutoffKnown: Boolean = false,
    val presetTimerSecs: Int = 0,
    val presetTimerKnown: Boolean = false,
)

sealed class ParsedMessage {
    data class Report(val report: MeterReport) : ParsedMessage()
    data class Reply(val reply: ReplyMessage) : ParsedMessage()
}

/**
 * Translates incoming BLE byte streams into MeterReport / ReplyMessage objects.
 * Handles packet reassembly (BLE notifications may split packets).
 */
class ProtocolParser {
    private val buffer = mutableListOf<Byte>()
    private var lastAh = -1.0
    private var accumulatedWh = 0.0

    fun reset() {
        buffer.clear()
        lastAh = -1.0
        accumulatedWh = 0.0
    }

    fun feed(data: ByteArray): List<ParsedMessage> {
        buffer.addAll(data.toList())
        val messages = mutableListOf<ParsedMessage>()

        while (buffer.size >= BleConstants.MIN_PACKET_SIZE) {
            val headerIdx = findHeader()
            if (headerIdx < 0) { buffer.clear(); break }
            if (headerIdx > 0) repeat(headerIdx) { buffer.removeAt(0) }
            if (buffer.size < 3) break

            val msgType = buffer[2].toInt() and 0xFF
            val expectedSize = getExpectedSize(msgType)
            if (expectedSize == null) {
                buffer.removeAt(0); buffer.removeAt(0); continue
            }
            if (buffer.size < expectedSize) break

            val packet = ByteArray(expectedSize) { buffer[it] }
            repeat(expectedSize) { buffer.removeAt(0) }

            if (!verifyChecksum(packet)) continue
            parsePacket(packet)?.let { messages.add(it) }
        }

        if (buffer.size > 256) buffer.clear()
        return messages
    }

    private fun findHeader(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0x55.toByte()) return i
        }
        return -1
    }

    private fun getExpectedSize(msgType: Int): Int? = when (msgType) {
        BleConstants.MSG_TYPE_REPORT -> 36
        BleConstants.MSG_TYPE_REPLY -> 8
        BleConstants.MSG_TYPE_COMMAND -> 10
        else -> null
    }

    private fun parsePacket(packet: ByteArray): ParsedMessage? = when (packet[2].toInt() and 0xFF) {
        BleConstants.MSG_TYPE_REPORT -> parseReport(packet)?.let { ParsedMessage.Report(it) }
        BleConstants.MSG_TYPE_REPLY -> ParsedMessage.Reply(parseReply(packet))
        else -> null
    }

    private fun parseReport(p: ByteArray): MeterReport? {
        if (p.size < 36) return null
        val deviceType = p[3].toInt() and 0xFF
        val name = when (deviceType) {
            BleConstants.DEVICE_AC -> "AC Meter"
            BleConstants.DEVICE_DC -> "DC Meter"
            BleConstants.DEVICE_USB -> "USB Meter"
            else -> "Unknown(0x%02X)".format(deviceType)
        }
        return when (deviceType) {
            BleConstants.DEVICE_AC -> parseAcReport(p, deviceType, name)
            BleConstants.DEVICE_USB -> parseUsbReport(p, deviceType, name)
            else -> parseDcReport(p, deviceType, name)
        }
    }

    private fun parseAcReport(p: ByteArray, dt: Int, name: String) = MeterReport(
        deviceType = dt, deviceTypeName = name,
        voltage = readU24(p, 4) / 10.0,
        current = readU24(p, 7) / 1000.0,
        power = readU24(p, 0x0A) / 10.0,
        energyWh = readU32(p, 0x0D) / 100.0,
        price = readU24(p, 0x11) / 100.0,
        frequency = readU16(p, 0x14) / 10.0,
        powerFactor = readU16(p, 0x16) / 1000.0,
        temperature = readU16(p, 0x18),
        hours = readU16(p, 0x1A),
        minutes = p[0x1C].toInt() and 0xFF,
        seconds = p[0x1D].toInt() and 0xFF,
        backlight = p[0x1E].toInt() and 0xFF,
    )

    private fun parseDcReport(p: ByteArray, dt: Int, name: String): MeterReport {
        val voltage = readU24(p, 4) / 10.0
        val current = readU24(p, 7) / 1000.0
        val energyAh = readU24(p, 0x0A) / 100.0

        // Integrate Wh locally since 0x0D is unreliable on some DL24 firmwares
        when {
            lastAh >= 0.0 && energyAh >= lastAh -> accumulatedWh += (energyAh - lastAh) * voltage
            energyAh < lastAh && lastAh >= 0.0 -> accumulatedWh = 0.0
        }
        lastAh = energyAh

        return MeterReport(
            deviceType = dt, deviceTypeName = name,
            voltage = voltage, current = current,
            power = voltage * current,
            energyWh = accumulatedWh, energyAh = energyAh,
            temperature = readU16(p, 0x18),
            hours = readU16(p, 0x1A),
            minutes = p[0x1C].toInt() and 0xFF,
            seconds = p[0x1D].toInt() and 0xFF,
            backlight = p[0x1E].toInt() and 0xFF,
        )
    }

    private fun parseUsbReport(p: ByteArray, dt: Int, name: String): MeterReport {
        val voltage = readU24(p, 4) / 100.0
        val current = readU24(p, 7) / 100.0
        return MeterReport(
            deviceType = dt, deviceTypeName = name,
            voltage = voltage, current = current,
            power = voltage * current,
            energyAh = readU24(p, 0x0A) / 1000.0,
            energyWh = readU32(p, 0x0D) / 100.0,
            usbDMinus = readU16(p, 0x11) / 100.0,
            usbDPlus = readU16(p, 0x13) / 100.0,
            temperature = readU16(p, 0x15),
            hours = readU16(p, 0x17),
            minutes = p[0x19].toInt() and 0xFF,
            seconds = p[0x1A].toInt() and 0xFF,
            backlight = p[0x1B].toInt() and 0xFF,
        )
    }

    private fun parseReply(p: ByteArray) = ReplyMessage(
        ok = p[3] == BleConstants.REPLY_OK[0] && p[4] == BleConstants.REPLY_OK[1],
        unsupported = p[3] == BleConstants.REPLY_UNSUPPORTED[0] && p[4] == BleConstants.REPLY_UNSUPPORTED[1],
    )

    private fun verifyChecksum(packet: ByteArray): Boolean {
        if (packet.size < 4) return false
        val expected = (packet.drop(2).dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF) xor BleConstants.CHECKSUM_XOR
        return (packet.last().toInt() and 0xFF) == expected
    }

    private fun readU16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
    private fun readU24(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)
    private fun readU32(d: ByteArray, o: Int) = (readU16(d, o).toLong() shl 16) or readU16(d, o + 2).toLong()
}

object CommandBuilder {
    private fun checksum(payload: ByteArray) =
        ((payload.sumOf { it.toInt() and 0xFF } and 0xFF) xor BleConstants.CHECKSUM_XOR).toByte()

    fun build(deviceType: Int, command: Int, value: Int = 0): ByteArray {
        val payload = byteArrayOf(
            BleConstants.MSG_TYPE_COMMAND.toByte(),
            deviceType.toByte(),
            command.toByte(),
            (value ushr 24 and 0xFF).toByte(),
            (value ushr 16 and 0xFF).toByte(),
            (value ushr 8 and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return BleConstants.MAGIC_HEADER + payload + byteArrayOf(checksum(payload))
    }

    fun buildDirect(cmd: Int, valInt: Int, valFrac: Int): ByteArray =
        byteArrayOf(0xB1.toByte(), 0xB2.toByte(), cmd.toByte(), valInt.toByte(), valFrac.toByte(), 0xB6.toByte())

    fun buildPx100Query(cmd: Int): ByteArray =
        byteArrayOf(0xB1.toByte(), 0xB2.toByte(), cmd.toByte(), 0x00, 0x00, 0xB6.toByte())

    fun resetWh(dt: Int = BleConstants.DEVICE_DC) = build(dt, BleConstants.CMD_RESET_WH)
    fun resetAh(dt: Int = BleConstants.DEVICE_DC) = build(dt, BleConstants.CMD_RESET_AH)
    fun resetDuration(dt: Int = BleConstants.DEVICE_DC) = build(dt, BleConstants.CMD_RESET_DURATION)
    fun resetAll(dt: Int = BleConstants.DEVICE_DC) = build(dt, BleConstants.CMD_RESET_ALL)
    fun setBacklight(sec: Int, dt: Int = BleConstants.DEVICE_DC) = build(dt, BleConstants.CMD_SET_BACKLIGHT, sec.coerceIn(0, 60))

    fun directOutput(enable: Boolean) = buildDirect(BleConstants.DIRECT_OUTPUT, if (enable) 1 else 0, 0)
    fun directCurrent(amps: Double): ByteArray {
        val i = amps.toInt(); val f = ((amps - i) * 100).toInt()
        return buildDirect(BleConstants.DIRECT_SET_CURRENT, i, f)
    }
    fun directVoltageCutoff(volts: Double): ByteArray {
        val i = volts.toInt(); val f = ((volts - i) * 100).toInt()
        return buildDirect(BleConstants.DIRECT_SET_VOLTAGE_CUTOFF, i, f)
    }
    fun directTimer(seconds: Int): ByteArray =
        byteArrayOf(0xB1.toByte(), 0xB2.toByte(), 0x04, ((seconds shr 8) and 0xFF).toByte(), (seconds and 0xFF).toByte(), 0xB6.toByte())
}
