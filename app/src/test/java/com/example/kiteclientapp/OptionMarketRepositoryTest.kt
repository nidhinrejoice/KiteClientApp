package com.example.kiteclientapp

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.Account
import com.example.kiteclientapp.feature.options.data.*
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetSocketAddress
import java.time.LocalDate

class OptionMarketRepositoryTest {
    @Test fun brokerCsvAndQuoteMappingRetainMissingDataAndCacheCatalog()= runBlocking {
        val expiry=LocalDate.now(OPTIONS_ZONE).plusDays(7)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var catalogs=0; var quoteRequests=0
        val header="instrument_token,tradingsymbol,name,expiry,strike,lot_size,instrument_type,exchange\n"
        server.createContext("/") { exchange ->
            assertEquals("token test-key:test-token",exchange.requestHeaders.getFirst("Authorization"))
            val body=when(exchange.requestURI.path) {
                "/instruments/NFO" -> { catalogs++; header+"1,CALL,NIFTY,$expiry,25000,65,CE,NFO\n2,PUT,NIFTY,$expiry,25000,65,PE,NFO\n" }
                "/instruments/NSE" -> { catalogs++; header+"3,NIFTY 50,NIFTY 50,,0,1,EQ,NSE\n" }
                "/quote" -> { quoteRequests++; """{"status":"success","data":{"NSE:NIFTY 50":{"instrument_token":3,"last_price":25000},"NFO:CALL":{"instrument_token":1,"last_price":100,"oi":1200,"volume":200,"depth":{"buy":[{"price":99}],"sell":[{"price":103}]}},"NFO:PUT":{"instrument_token":2,"last_price":95,"volume":200}}}""" }
                else -> error("Unexpected broker endpoint")
            }
            exchange.responseHeaders.add("Content-Type",if(exchange.requestURI.path=="/quote") "application/json" else "text/csv")
            val bytes=body.toByteArray(); exchange.sendResponseHeaders(200,bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val persistence=object:SessionPersistence { override fun read():StoredSession?=null; override fun write(session:StoredSession){}; override fun clear(){} }
            val sessions=SessionStore(persistence); sessions.save("test-key","test-token",Account("TEST","Test"))
            val repo=OptionMarketRepository(Retrofit.Builder().baseUrl("http://127.0.0.1:${server.address.port}/").addConverterFactory(GsonConverterFactory.create()).build(),sessions,KiteConfiguration("test-key",""))
            val chain=repo.load("NIFTY",null)
            assertEquals(expiry,chain.expiry); assertEquals(65,chain.legs.first().lotSize)
            assertEquals(101.0,chain.leg(25000.0,true)!!.price!!,0.0)
            assertEquals(95.0,chain.leg(25000.0,false)!!.price!!,0.0)
            assertNull(chain.pcr); assertNull(chain.maxPain)
            assertEquals(25006.0,chain.forward,1.0)
            repo.load("NIFTY",expiry)
            assertEquals(2,catalogs); assertEquals(2,quoteRequests)
        } finally { server.stop(0) }
    }
}
