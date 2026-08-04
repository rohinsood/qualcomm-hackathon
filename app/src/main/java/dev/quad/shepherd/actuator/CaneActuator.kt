package dev.quad.shepherd.actuator

import dev.quad.shepherd.guidance.GuidanceEngine

/**
 * Output boundary for external hardware, mirroring Shepherd's motorized
 * cane. The phone remains the vision brain; an actuator just receives
 * steering commands. v1 ships [NoOpActuator]; wire a BLE implementation
 * here when the hardware exists.
 */
interface CaneActuator {
    fun connect()
    fun sendGuidance(guidance: GuidanceEngine.Guidance)
    fun disconnect()
}

/** Default: phone-only operation. */
class NoOpActuator : CaneActuator {
    override fun connect() {}
    override fun sendGuidance(guidance: GuidanceEngine.Guidance) {}
    override fun disconnect() {}
}

/**
 * Skeleton for a Shepherd-style BLE cane (ESP32 + motorized omni wheel).
 *
 * Shepherd uses a compact 12-byte command protocol over a BLE GATT
 * characteristic. [encodePacket] reproduces that idea; the GATT plumbing
 * (scan -> connect -> discover services -> write characteristic) is left
 * for when hardware exists, since it can't be tested without a device.
 *
 * Suggested packet layout:
 *   [0]     header 0xA5
 *   [1]     sequence number
 *   [2]     command: 0=coast, 1=steer, 2=stop
 *   [3]     steer, signed: -100 (hard left) .. +100 (hard right)
 *   [4]     severity: 0=clear 1=caution 2=danger
 *   [5..6]  nearest obstacle distance, centimeters, little-endian
 *   [7..10] reserved
 *   [11]    XOR checksum of bytes 0..10
 */
class BleCaneActuator : CaneActuator {

    private var sequence = 0

    override fun connect() {
        // TODO: BluetoothLeScanner -> connectGatt -> discoverServices ->
        // cache the command characteristic. Requires BLUETOOTH_CONNECT /
        // BLUETOOTH_SCAN permissions in the manifest when enabled.
    }

    override fun sendGuidance(guidance: GuidanceEngine.Guidance) {
        @Suppress("UNUSED_VARIABLE")
        val packet = encodePacket(guidance)
        // TODO: gatt.writeCharacteristic(commandCharacteristic, packet, WRITE_TYPE_NO_RESPONSE)
    }

    override fun disconnect() {
        // TODO: close GATT
    }

    internal fun encodePacket(guidance: GuidanceEngine.Guidance): ByteArray {
        val packet = ByteArray(12)
        packet[0] = 0xA5.toByte()
        packet[1] = (sequence++ and 0xFF).toByte()
        packet[2] = when (guidance.severity) {
            GuidanceEngine.Severity.CLEAR -> 0
            GuidanceEngine.Severity.CAUTION -> 1
            GuidanceEngine.Severity.DANGER -> 2
        }
        packet[3] = (guidance.steer * 100f).toInt().coerceIn(-100, 100).toByte()
        packet[4] = packet[2]
        val distCm = ((guidance.nearest?.distanceMeters ?: 50f) * 100f).toInt().coerceIn(0, 65535)
        packet[5] = (distCm and 0xFF).toByte()
        packet[6] = ((distCm shr 8) and 0xFF).toByte()
        var checksum = 0
        for (i in 0..10) checksum = checksum xor packet[i].toInt()
        packet[11] = (checksum and 0xFF).toByte()
        return packet
    }
}
