package io.nisfeb.talon.ui

import io.nisfeb.talon.ui.screens.friendlyError
import kotlin.test.Test
import kotlin.test.assertEquals

class SignInErrorTest {
    // A timeout read as "Couldn't sign in: Request timeout has expired [url=…]".
    @Test
    fun `a network hiccup is said as one, and a raw reply never reaches the line`() {
        assertEquals("The ship didn't answer in time. Check your connection and try again.",
            friendlyError(kotlinx.io.IOException("Request timeout has expired [url=https://ship.test/~/login]")))
        assertEquals("Wrong +code. Try again.", friendlyError(IllegalStateException("login HTTP 400")))
        assertEquals("Couldn't sign in: something went wrong.", friendlyError(IllegalStateException("HTTP 502 <html>Bad Gateway</html>")))
    }
}
