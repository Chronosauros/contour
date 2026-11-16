package io.github.chronosauros.contour.desktop.usb

import io.github.chronosauros.contour.core.DeviceTarget
import org.hid4java.HidDevice

/** Attached USB HID device identified against the Contour device catalog. */
class MacDacHandle(
    val device: HidDevice,
    val target: DeviceTarget,
    val key: String = "${device.path}:${device.vendorId}:${device.productId}"
) {
    companion object {
        fun of(device: HidDevice): MacDacHandle? {
            val target = DeviceTarget.findDevice(device.vendorId, device.productId, device.product)
                ?: return null
            return MacDacHandle(device, target)
        }
    }
}
