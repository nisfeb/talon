package io.nisfeb.talon.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationBadgeTest {
    @Test
    fun `a bubble writes the count, and 99+ past 99`() {
        assertEquals("1", notificationBadgeLabel(1))
        assertEquals("99", notificationBadgeLabel(99))
        assertEquals("99+", notificationBadgeLabel(100))
        assertEquals("99+", notificationBadgeLabel(12_345))
    }
}
