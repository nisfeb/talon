package io.nisfeb.talon.urbit

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizeShipUrlTest {

    @Test
    fun bareHostGetsHttpsPrefix() {
        assertEquals("https://ship.example.com", normalizeShipUrl("ship.example.com"))
    }

    @Test
    fun bareHostWithPortGetsHttpsPrefix() {
        assertEquals("https://192.168.1.42:8080", normalizeShipUrl("192.168.1.42:8080"))
    }

    @Test
    fun explicitHttpIsPreserved() {
        assertEquals("http://localhost:8080", normalizeShipUrl("http://localhost:8080"))
    }

    @Test
    fun explicitHttpsIsPreserved() {
        assertEquals("https://ship.example.com", normalizeShipUrl("https://ship.example.com"))
    }

    @Test
    fun mixedCaseSchemeIsRecognized() {
        // Don't re-prefix if the user typed "HTTP://" — toHttpUrl()
        // handles the casing fine.
        assertEquals("HTTP://localhost:8080", normalizeShipUrl("HTTP://localhost:8080"))
    }

    @Test
    fun trailingSlashIsStripped() {
        assertEquals("https://ship.example.com", normalizeShipUrl("https://ship.example.com/"))
    }

    @Test
    fun trailingSlashStrippedFromBareHost() {
        assertEquals("https://ship.example.com", normalizeShipUrl("ship.example.com/"))
    }

    @Test
    fun whitespaceIsStripped() {
        assertEquals("https://ship.example.com", normalizeShipUrl("  ship.example.com  "))
    }
}
