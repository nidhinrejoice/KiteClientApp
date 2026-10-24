package com.example.kiteclientapp.feature.scanners.data


import com.example.kiteclientapp.data.*
import okhttp3.ResponseBody
import retrofit2.http.*

interface ScannerKiteApi {
    @GET("instruments/{exchange}")
    suspend fun instruments(
        @Header("Authorization") auth: String,
        @Path("exchange") exchange: String
    ): ResponseBody

    @GET("instruments/historical/{token}/day")
    suspend fun candles(
        @Header("Authorization") auth: String,
        @Path("token") token: Long,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("oi") oi: Int = 1
    ): Envelope<ScannerCandlesDto>

    @GET("quote")
    suspend fun quotes(
        @Header("Authorization") auth: String,
        @Query("i") keys: List<String>
    ): Envelope<Map<String, ScannerQuoteDto>>

    // No Authorization header is ever supplied to this public constituent source.
    @Headers("User-Agent: Mozilla/5.0")
    @GET("https://www.niftyindices.com/IndexConstituent/ind_nifty100list.csv")
    suspend fun constituents(): ResponseBody
}