package com.example.kiteclientapp

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.kiteclientapp.data.KeystoreSessionPersistence
import com.example.kiteclientapp.data.SessionStore
import com.example.kiteclientapp.domain.Account
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionPersistenceTest {
    @Test fun sessionRestoresFromEncryptedDiskAndLogoutRemovesIt() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "session-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        val clock = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC)
        val account = Account("SYNTHETIC_USER", "Synthetic Investor")
        try {
            SessionStore(KeystoreSessionPersistence(context), clock).save("synthetic-key", "synthetic-access-token", account)
            val file = File(directory, "kite-session.bin")
            assertTrue(file.exists())
            val ciphertext = file.readText(Charsets.ISO_8859_1)
            assertFalse(ciphertext.contains("synthetic-access-token"))
            assertFalse(ciphertext.contains(account.id))
            assertFalse(ciphertext.contains(account.name))
            val restarted = SessionStore(KeystoreSessionPersistence(context), clock)
            assertEquals(account, restarted.restore("synthetic-key"))
            assertEquals("token synthetic-key:synthetic-access-token", restarted.authorization("synthetic-key"))
            restarted.clear()
            assertFalse(file.exists())
            assertNull(SessionStore(KeystoreSessionPersistence(context), clock).restore("synthetic-key"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun tamperedSavedSessionRequiresFreshLogin() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "session-tamper-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
        val clock = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC)
        try {
            SessionStore(KeystoreSessionPersistence(context), clock).save("synthetic-key", "synthetic-token", Account("TEST", "Test"))
            val file = File(directory, "kite-session.bin")
            val data = file.readBytes()
            data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
            file.writeBytes(data)
            assertNull(SessionStore(KeystoreSessionPersistence(context), clock).restore("synthetic-key"))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
