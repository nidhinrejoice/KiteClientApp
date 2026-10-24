package com.example.kiteclientapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.kiteclientapp.domain.Account
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Called on the SessionStore I/O dispatcher, never from the UI thread. */
@Singleton
class KeystoreSessionPersistence @Inject constructor(@param:ApplicationContext private val context: Context) : SessionPersistence {
    private val file get() = AtomicFile(File(context.noBackupFilesDir, "kite-session.bin"))
    private val alias = "kite-client-session-v1"
    private val aad get() = "${context.packageName}:session:v1".toByteArray(Charsets.UTF_8)

    override fun read(): StoredSession? {
        return try {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size in 32..16384)
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == 1)
            require(input.readInt() == 12)
            val iv = ByteArray(12).also(input::readFully)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val decoded = cipher.doFinal(input.readBytes())
            try {
                val json = JSONObject(String(decoded, Charsets.UTF_8))
                val apiKey = json.getString("apiKey")
                val token = json.getString("accessToken")
                val id = json.getString("userId")
                val name = json.getString("userName")
                val expires = json.getLong("expiresAtMillis")
                require(listOf(apiKey, token, id, name).all { it.isNotBlank() } && expires > 0)
                StoredSession(apiKey, token, Account(id, name), expires)
            } finally { decoded.fill(0) }
        } catch (_: FileNotFoundException) { null
        } catch (_: Exception) {
            // Corrupted ciphertext, missing keys, and authentication-tag failures require sign-in.
            // Do not log the file, token, account details, or cryptographic exception.
            clear()
            null
        }
    }
    override fun write(session: StoredSession) {
        val plaintext = JSONObject().put("apiKey", session.apiKey).put("accessToken", session.accessToken)
            .put("userId", session.account.id).put("userName", session.account.name)
            .put("expiresAtMillis", session.expiresAtMillis).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(1); out.writeInt(cipher.iv.size); out.write(cipher.iv); out.write(encrypted)
                }
            }.toByteArray()
            val atomic = file
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        } finally { plaintext.fill(0) }
    }
    override fun clear() {
        val atomic = file
        atomic.delete()
        check(!atomic.baseFile.exists()) { "Unable to remove saved session" }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
