package org.evsyukov.perigee.sync

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProvisionTest {
    private val name = "bookorbit.koplugin/bookorbit_provision.lua"
    private val key = "0123456789abcdef0123456789abcdef"
    private val provision = """
        return {
            server_url = "https://other.example",
            username = "Reader \"1\"",
            userkey = "$key",
        }
    """.trimIndent()

    @Test
    fun onlyTheExpectedUsersKeyIsRead() {
        val packageBytes = zip(
            "bookorbit.koplugin/main.lua" to "error('This file must never run')".toByteArray(),
            name to provision.toByteArray(),
        )
        assertEquals(key, readProvisionedDeviceKey(packageBytes.inputStream(), "Reader \"1\""))
        assertNull(readProvisionedDeviceKey(packageBytes.inputStream(), "Reader \"2\""))
    }

    @Test
    fun duplicateOrInvalidKeysAreRejected() {
        val duplicate = zip(name to "$provision\nuserkey = \"$key\",".toByteArray())
        val invalid = zip(name to provision.replace(key, "not a key").toByteArray())
        assertNull(readProvisionedDeviceKey(duplicate.inputStream(), "Reader \"1\""))
        assertNull(readProvisionedDeviceKey(invalid.inputStream(), "Reader \"1\""))
    }

    @Test
    fun anOversizedPackageCannotSupplyAKey() {
        val expanded = zip("unrelated" to ByteArray(12 * 1024 * 1024 + 1), name to provision.toByteArray())
        val oversizedProvision = zip(name to (provision + " ".repeat(4096)).toByteArray())
        assertNull(readProvisionedDeviceKey(expanded.inputStream(), "Reader \"1\""))
        assertNull(readProvisionedDeviceKey(oversizedProvision.inputStream(), "Reader \"1\""))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((path, contents) in entries) {
                out.putNextEntry(ZipEntry(path))
                out.write(contents)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
