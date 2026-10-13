package org.evsyukov.perigee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainHttpTest {
    @Test
    fun addressThatSaysHttpIsUnprotected() {
        assertTrue(isPlainHttp("http://192.168.1.5:8080/opds"))
        assertTrue(isPlainHttp("  HTTP://books.local "))
    }

    @Test
    fun httpsAndAddressesWithoutASchemeAreProtected() {
        assertFalse(isPlainHttp("https://books.example/opds"))
        // Taken as https when the scheme is left out.
        assertFalse(isPlainHttp("books.example/opds"))
        assertFalse(isPlainHttp("httpbooks.example"))
        assertFalse(isPlainHttp(""))
    }
}
