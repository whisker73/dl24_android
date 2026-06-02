package com.dl24.monitor.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHAR_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val NAME_FILTERS = listOf("DL24", "AT24", "UD18", "-BLE", "-SPP")

    val MAGIC_HEADER = byteArrayOf(0xFF.toByte(), 0x55)
    const val CHECKSUM_XOR = 0x44

    const val MSG_TYPE_REPORT = 0x01
    const val MSG_TYPE_REPLY = 0x02
    const val MSG_TYPE_COMMAND = 0x11

    const val DEVICE_AC = 0x01
    const val DEVICE_DC = 0x02
    const val DEVICE_USB = 0x03

    // Atorch FF55 commands
    const val CMD_RESET_WH = 0x01
    const val CMD_RESET_AH = 0x02
    const val CMD_RESET_DURATION = 0x03
    const val CMD_RESET_ALL = 0x05
    const val CMD_SET_BACKLIGHT = 0x21
    const val CMD_SETUP = 0x31
    const val CMD_ENTER = 0x32

    // Direct control B1 B2 protocol
    const val DIRECT_OUTPUT = 0x01
    const val DIRECT_SET_CURRENT = 0x02
    const val DIRECT_SET_VOLTAGE_CUTOFF = 0x03
    const val DIRECT_SET_TIMER = 0x04

    // PX100 query commands
    const val PX100_QUERY_OUTPUT_STATE = 0x10
    const val PX100_QUERY_PRESET_CURRENT = 0x17
    const val PX100_QUERY_PRESET_CUTOFF = 0x18
    const val PX100_QUERY_PRESET_TIMER = 0x19

    val REPLY_OK = byteArrayOf(0x02, 0x01)
    val REPLY_UNSUPPORTED = byteArrayOf(0x02, 0x03)

    const val MIN_PACKET_SIZE = 7
    const val REPORT_PACKET_SIZE = 36
}
