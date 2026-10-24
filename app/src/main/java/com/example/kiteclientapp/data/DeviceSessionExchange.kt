package com.example.kiteclientapp.data

import com.example.kiteclientapp.domain.ConfigurationMissing
import java.security.MessageDigest
import javax.inject.Inject
import retrofit2.http.*

interface DirectKiteApi {
    @FormUrlEncoded
    @POST("session/token")
    suspend fun exchange(@Field("api_key") apiKey: String, @Field("request_token") requestToken: String,
        @Field("checksum") checksum: String): Envelope<ExchangeResponse>
}
class DeviceSessionExchange @Inject constructor(
    private val api: DirectKiteApi, private val configuration: KiteConfiguration,
) : SessionExchange {
    override val usesDeviceSecret = true
    override suspend fun isAvailable() = configuration.isConfigured
    override suspend fun exchange(requestToken: String): ExchangeResponse {
        if (!isAvailable()) throw ConfigurationMissing()
        require(requestToken.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(configuration.apiKey.toByteArray(Charsets.UTF_8))
        digest.update(requestToken.toByteArray(Charsets.UTF_8))
        digest.update(configuration.apiSecret.toByteArray(Charsets.UTF_8))
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val response = api.exchange(configuration.apiKey, requestToken, checksum)
        check(response.status == "success")
        return checkNotNull(response.data)
    }
}
