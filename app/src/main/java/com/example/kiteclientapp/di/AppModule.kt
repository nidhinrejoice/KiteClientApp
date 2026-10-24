package com.example.kiteclientapp.di

import com.example.kiteclientapp.BuildConfig
import com.example.kiteclientapp.data.*
import com.example.kiteclientapp.domain.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun auth(implementation: KiteAuthRepository): AuthRepository
    @Binds
    @Singleton
    abstract fun persistence(implementation: KeystoreSessionPersistence): SessionPersistence
    @Binds
    @Singleton
    abstract fun portfolio(implementation: KitePortfolioRepository): PortfolioRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun configuration() = KiteConfiguration(
        BuildConfig.KITE_API_KEY,
        BuildConfig.KITE_API_SECRET,
        BuildConfig.KITE_REDIRECT_URL
    )

    @Provides
    @Singleton
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("X-Kite-Version", "3").build()
            )
        }
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.kite.trade/").client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()

    @Provides
    @Singleton
    fun kiteApi(retrofit: Retrofit): KiteApi = retrofit.create(KiteApi::class.java)
    @Provides
    @Singleton
    fun directApi(retrofit: Retrofit): DirectKiteApi = retrofit.create(DirectKiteApi::class.java)
    @Provides
    @Singleton
    fun exchange(device: DeviceSessionExchange): SessionExchange = device
    @Provides
    @Singleton
    fun sessions(persistence: SessionPersistence) = SessionStore(persistence)
    @Provides
    fun restoreSession(repository: AuthRepository) = RestoreSession(repository)
    @Provides
    fun signIn(repository: AuthRepository) = SignIn(repository)
    @Provides
    fun signOut(repository: AuthRepository) = SignOut(repository)
    @Provides
    fun loadPortfolio(repository: PortfolioRepository) = LoadPortfolio(repository)
}
