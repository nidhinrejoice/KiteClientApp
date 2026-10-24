package com.example.kiteclientapp.feature.scanners.di

import com.example.kiteclientapp.feature.scanners.data.*
import com.example.kiteclientapp.feature.scanners.domain.*
import dagger.*
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerRepositoryModule {
    @Binds
    @Singleton
    abstract fun market(source: KiteScannerMarketSource): ScannerMarketSource
    @Binds
    @Singleton
    abstract fun history(repository: LocalScanHistoryRepository): ScanHistoryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object ScannerUseCaseModule {
    @Provides
    fun run(market: ScannerMarketSource) = RunScanner(market)
    @Provides
    fun history(repository: ScanHistoryRepository) = LoadScanHistory(repository)
    @Provides
    fun save(repository: ScanHistoryRepository) = SaveScan(repository)
}
