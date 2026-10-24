package com.example.kiteclientapp.data

import com.example.kiteclientapp.domain.Account
import com.example.kiteclientapp.domain.SessionExpired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Not a data class: generated toString() must never print the access token.
class StoredSession(val apiKey: String, val accessToken: String, val account: Account, val expiresAtMillis: Long)
interface SessionPersistence {
    fun read(): StoredSession?
    fun write(session: StoredSession)
    fun clear()
}

fun nextKiteSessionExpiry(now: Instant): Instant {
    val india = ZoneId.of("Asia/Kolkata")
    val local = now.atZone(india)
    val today = local.toLocalDate().atTime(6, 0).atZone(india)
    return (if (today.toInstant() > now) today else today.plusDays(1)).toInstant()
}

class SessionStore(private val persistence: SessionPersistence, private val clock: Clock = Clock.systemUTC()) {
    private val mutex = Mutex()
    @Volatile private var current: StoredSession? = null
    @Volatile var isDemo: Boolean = false
        private set
    val token: String? get() = current?.accessToken
    val accountId: String? get() = current?.account?.id

    suspend fun save(apiKey: String, token: String, account: Account) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(apiKey.isNotBlank() && token.isNotBlank() && account.id.isNotBlank() && account.name.isNotBlank() && !account.isDemo)
            val session = StoredSession(apiKey, token, account, nextKiteSessionExpiry(clock.instant()).toEpochMilli())
            persistence.write(session)
            current = session
            isDemo = false
        }
    }
    suspend fun restore(apiKey: String): Account? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = current ?: persistence.read()
            if (session == null || session.apiKey != apiKey || session.expiresAtMillis <= clock.millis()) {
                current = null
                isDemo = false
                persistence.clear()
                null
            } else {
                current = session
                isDemo = false
                session.account
            }
        }
    }
    suspend fun demo() = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistence.clear()
            current = null
            isDemo = true
        }
    }
    fun authorization(apiKey: String): String {
        val session = current ?: throw SessionExpired()
        if (session.apiKey != apiKey || session.expiresAtMillis <= clock.millis()) throw SessionExpired()
        return "token $apiKey:${session.accessToken}"
    }
    suspend fun clear() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            current = null
            isDemo = false
            persistence.clear()
        }
    }
}
