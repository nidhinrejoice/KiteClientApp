package com.example.kiteclientapp

import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.*
import com.example.kiteclientapp.feature.scanners.data.*
import com.example.kiteclientapp.feature.scanners.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.*

class ScannerSourceTest {
    private fun sessions()=SessionStore(object: SessionPersistence { override fun read(): StoredSession?=null; override fun write(session: StoredSession) {}; override fun clear() {} })
    @Test fun `all eight demo scanners run without a reachable broker and never create live results`() = runBlocking {
        val sessions=sessions(); sessions.demo()
        val source=KiteScannerMarketSource(Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).build(),sessions,KiteConfiguration("",""),KiteMarketRequestGate())
        val owner=ScanOwner("DEMO",true)
        for(strategy in ScannerCatalog.strategies) {
            val run=RunScanner(source)(owner,strategy,strategy.defaults+("limit" to 3.0)) {}
            assertTrue(strategy.id,run.demo); assertEquals(strategy.id,3,run.total); assertEquals(strategy.id,run.total,run.rows.size+run.skipped.size+run.noSignal)
        }
        sessions.clear(); assertThrows(SessionExpired::class.java) { source.checkOwner(owner) }; Unit
    }
    @Test fun `source rejects another account before any market requests`() = runBlocking {
        val sessions=sessions(); sessions.save("key","test-token",Account("A","A"))
        val source=KiteScannerMarketSource(Retrofit.Builder().baseUrl("http://127.0.0.1:1/").addConverterFactory(GsonConverterFactory.create()).build(),sessions,KiteConfiguration("key",""),KiteMarketRequestGate())
        assertThrows(SessionExpired::class.java) { source.checkOwner(ScanOwner("B",false)) }
        assertThrows(SessionExpired::class.java) { source.checkOwner(ScanOwner("DEMO",true)) }; Unit
    }
    @Test fun `scanner CSV parser handles quoted names and escaped quotes`() {
        assertEquals(listOf("1","ABC","A, B \"Company\"","EQ"),scannerCsv("1,ABC,\"A, B \"\"Company\"\"\",EQ"))
    }
    @Test fun `public constituent download has compatible headers and never sends broker authorization`() = runBlocking {
        val sessions=sessions(); sessions.save("key","test-token",Account("A","A"))
        var requests=0
        val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request(); requests++
            assertEquals("www.niftyindices.com",request.url.host)
            assertEquals("Mozilla/5.0",request.header("User-Agent"))
            assertNull(request.header("Authorization"))
            val csv="Symbol,Company Name\n"+(1..100).joinToString("\n") { "TEST$it,Test $it" }
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(csv.toResponseBody("text/csv".toMediaType())).build()
        }.build()
        val source=KiteScannerMarketSource(Retrofit.Builder().baseUrl("https://api.kite.trade/").client(client).addConverterFactory(GsonConverterFactory.create()).build(),sessions,KiteConfiguration("key",""),KiteMarketRequestGate())
        assertEquals(100,source.nifty100(ScanOwner("A",false)).size)
        assertEquals(100,source.nifty100(ScanOwner("A",false)).size)
        assertEquals(1,requests)
    }

}
