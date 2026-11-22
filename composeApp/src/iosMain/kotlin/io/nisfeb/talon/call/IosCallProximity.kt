package io.nisfeb.talon.call

import platform.UIKit.UIDevice

/**
 * The screen off at the ear on an iPhone: proximity monitoring, which
 * blanks the screen while the sensor is covered. Nothing turned it on,
 * so a call on the earpiece left the screen live against the cheek.
 * Set on the main thread, where [CallProximityEffect] runs. A device
 * without the sensor (an iPad) leaves it off by itself.
 */
object IosCallProximity : CallProximity {
    override fun screenOffNearFace(on: Boolean) {
        val device = UIDevice.currentDevice
        if (device.proximityMonitoringEnabled != on) device.proximityMonitoringEnabled = on
    }
}
