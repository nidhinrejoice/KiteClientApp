package com.example.kiteclientapp

import com.example.kiteclientapp.data.SessionPersistence
import com.example.kiteclientapp.data.StoredSession

class MemorySessionPersistence : SessionPersistence {
    var saved: StoredSession? = null
    override fun read() = saved
    override fun write(session: StoredSession) { saved = session }
    override fun clear() { saved = null }
}
