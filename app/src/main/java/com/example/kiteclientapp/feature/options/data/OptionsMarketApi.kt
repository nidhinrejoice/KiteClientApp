package com.example.kiteclientapp.feature.options.data

import com.example.kiteclientapp.data.Envelope
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface OptionsMarketApi {
    @GET("instruments/{exchange}")
    suspend fun instruments(
        @Header("Authorization") auth: String,
        @Path("exchange") exchange: String
    ): ResponseBody

    @GET("quote")
    suspend fun quotes(
        @Header("Authorization") auth: String,
        @Query("i") instruments: List<String>
    ): Envelope<Map<String, MarketQuote>>

    @GET("instruments/historical/{token}/day")
    suspend fun history(
        @Header("Authorization") auth: String, @Path("token") token: Long,
        @Query("from") from: String, @Query("to") to: String, @Query("oi") oi: Int = 1
    ): Envelope<Candles>
}