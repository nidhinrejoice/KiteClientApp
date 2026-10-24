package com.example.kiteclientapp

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionStoreTest {
    private val account = Account("AB1234", "Investor")
    private val clock = Clock.fixed(Instant.parse("2026-10-21T15:00:00Z"), ZoneOffset.UTC)

    @Test fun `fresh store restores account and token from persisted session`() = runBlocking {
        val persistence = MemorySessionPersistence()
        SessionStore(persistence, clock).save("public", "synthetic-token", account)
        val restarted = SessionStore(persistence, clock)
        assertEquals(account, restarted.restore("public"))
        assertEquals("token public:synthetic-token", restarted.authorization("public"))
    }
    @Test fun `expired session is removed instead of restored`() = runBlocking {
        val persistence = MemorySessionPersistence()
        SessionStore(persistence, clock).save("public", "synthetic-token", account)
        val nextMorning = Clock.fixed(Instant.parse("2026-10-22T00:30:00Z"), ZoneOffset.UTC)
        val restarted = SessionStore(persistence, nextMorning)
        assertNull(restarted.restore("public"))
        assertNull(restarted.token)
        assertNull(persistence.saved)
    }
    @Test fun `logout prevents a new instance from restoring the token`() = runBlocking {
        val persistence = MemorySessionPersistence()
        val session = SessionStore(persistence, clock)
        session.save("public", "synthetic-token", account)
        session.clear()
        assertNull(session.token)
        assertNull(SessionStore(persistence, clock).restore("public"))
    }
    @Test fun `changing api key cannot restore another app session`() = runBlocking {
        val persistence = MemorySessionPersistence()
        SessionStore(persistence, clock).save("public", "synthetic-token", account)
        assertNull(SessionStore(persistence, clock).restore("different"))
        assertNull(persistence.saved)
    }
    @Test fun `demo clears a previous live session and is not persisted`() = runBlocking {
        val persistence = MemorySessionPersistence()
        val session = SessionStore(persistence, clock)
        session.save("public", "synthetic-token", account)
        session.demo()
        assertTrue(session.isDemo)
        assertNull(session.token)
        assertNull(SessionStore(persistence, clock).restore("public"))
    }
    @Test fun `expiry follows the next 6am IST boundary across midnight`() {
        assertEquals(Instant.parse("2026-10-22T00:30:00Z"), nextKiteSessionExpiry(Instant.parse("2026-10-21T15:00:00Z")))
        assertEquals(Instant.parse("2026-10-22T00:30:00Z"), nextKiteSessionExpiry(Instant.parse("2026-10-22T00:29:59Z")))
        assertEquals(Instant.parse("2026-10-23T00:30:00Z"), nextKiteSessionExpiry(Instant.parse("2026-10-22T00:30:00Z")))
    }
}
