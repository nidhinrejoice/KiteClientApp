package com.example.kiteclientapp.data

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal
import retrofit2.http.*

data class Envelope<T>(val status: String, val data: T?)
data class HoldingDto(
    val tradingsymbol: String, val exchange: String, val quantity: Int,
    val t1_quantity: Int, val used_quantity: Int,
    val average_price: BigDecimal, val last_price: BigDecimal, val close_price: BigDecimal,
    val collateral_quantity: Int? = null, val collateral_type: String? = null,
)
data class PositionDto(
    val tradingsymbol: String, val exchange: String, val product: String,
    val quantity: Int, val last_price: BigDecimal, val pnl: BigDecimal,
)
data class PositionsDto(val net: List<PositionDto>)
interface KiteApi {
    @GET("portfolio/holdings")
    suspend fun holdings(@Header("Authorization") authorization: String): Envelope<List<HoldingDto>>
    @GET("portfolio/positions")
    suspend fun positions(@Header("Authorization") authorization: String): Envelope<PositionsDto>
    @DELETE("session/token")
    suspend fun logout(@Header("Authorization") authorization: String, @Query("api_key") apiKey: String,
        @Query("access_token") accessToken: String): Envelope<Any>
}

data class ExchangeResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("user_name") val userName: String,
)
