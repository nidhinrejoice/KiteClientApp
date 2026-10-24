package com.example.kiteclientapp.data

interface SessionExchange {
    val usesDeviceSecret: Boolean
    suspend fun isAvailable(): Boolean
    suspend fun exchange(requestToken: String): ExchangeResponse
}
