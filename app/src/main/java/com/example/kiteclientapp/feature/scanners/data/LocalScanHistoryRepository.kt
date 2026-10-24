package com.example.kiteclientapp.feature.scanners.data

import android.content.Context
import android.util.AtomicFile
import com.example.kiteclientapp.feature.scanners.domain.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** App-private, excluded from backup, account-isolated results. Never contains session tokens. */
@Singleton
class LocalScanHistoryRepository @Inject constructor(@param:ApplicationContext private val context: Context): ScanHistoryRepository {
    private val mutex=Mutex(); private val gson=Gson()
    private fun file(owner: ScanOwner): AtomicFile {
        val digest=MessageDigest.getInstance("SHA-256").digest("${owner.id}:${owner.demo}".toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(File(context.noBackupFilesDir,"scanners").apply { mkdirs() },"$digest.json"))
    }
    private fun read(owner: ScanOwner): List<ScanRun> {
        val file=file(owner); if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return emptyList()
        return file.openRead().bufferedReader().use { gson.fromJson<List<ScanRun>>(it,object:TypeToken<List<ScanRun>>(){}.type) }?:error("Scan history is unreadable.")
    }
    override suspend fun load(owner: ScanOwner): List<ScanRun> = withContext(Dispatchers.IO) { mutex.withLock { read(owner).sortedByDescending { it.finishedAt } } }
    override suspend fun save(owner: ScanOwner,run: ScanRun) = withContext(Dispatchers.IO) { mutex.withLock {
        require(run.demo==owner.demo); val runs=(listOf(run)+read(owner).filter { it.id!=run.id }).sortedByDescending { it.finishedAt }.take(100)
        val file=file(owner); val stream=file.startWrite()
        try { stream.write(gson.toJson(runs).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) } catch(e:Exception) { file.failWrite(stream); throw e }
    } }
}
