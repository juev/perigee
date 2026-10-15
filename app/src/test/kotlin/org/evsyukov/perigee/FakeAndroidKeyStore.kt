package org.evsyukov.perigee

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * The Android Keystore as the JVM has none: AES keys made by the JDK and kept in memory.
 * Passwords and tokens are sealed in tests the way they are on a device, only with a key
 * that does not leave the process.
 */
object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"

    fun install() {
        if (Security.getProvider(NAME) == null) Security.addProvider(Keystore())
    }

    private val keys = ConcurrentHashMap<String, SecretKey>()

    private class Keystore : Provider(NAME, 1.0, "Android Keystore kept in memory, for tests") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
        override fun engineContainsAlias(alias: String) = keys.containsKey(alias)
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = keys.containsKey(alias)
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("a key needs a spec")
        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("a key needs a spec")

        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()
            keys[checkNotNull(alias)] = key
            return key
        }
    }
}
