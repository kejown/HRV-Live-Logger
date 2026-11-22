package com.rrlogger.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 唯一的数据访问入口。Repository 不做业务判断，
 * 会话计数等"当前状态"由 RecordingService 持有并定期落库。
 */
class RrRepository(
    private val sessionDao: SessionDao,
    private val rrDao: RrRecordDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    // ---------------- Session ----------------

    suspend fun createSession(label: String, startTime: Long = System.currentTimeMillis()): Long =
        withContext(io) { sessionDao.insert(Session(startTime = startTime, label = label)) }

    suspend fun findUnfinishedSession(): Session? =
        withContext(io) { sessionDao.findUnfinished() }

    suspend fun getSession(id: Long): Session? =
        withContext(io) { sessionDao.findById(id) }

    suspend fun finishSession(id: Long, endTime: Long, totalBeats: Int, avgHr: Int, dropped: Int) =
        withContext(io) { sessionDao.finish(id, endTime, totalBeats, avgHr, dropped) }

    suspend fun updateStats(id: Long, totalBeats: Int, avgHr: Int, dropped: Int) =
        withContext(io) { sessionDao.updateStats(id, totalBeats, avgHr, dropped) }

    suspend fun updateLabel(id: Long, label: String) =
        withContext(io) { sessionDao.updateLabel(id, label) }

    suspend fun deleteSession(id: Long) = withContext(io) {
        rrDao.deleteBySession(id)
        sessionDao.delete(id)
    }

    fun observeSessions(): Flow<List<Session>> =
        sessionDao.observeAll().flowOn(io)

    suspend fun listSessions(): List<Session> = withContext(io) { sessionDao.findAll() }

    // ---------------- RrRecord ----------------

    /** 每条有效 RR 立即插入，不攒批 —— 崩溃最多丢一拍。 */
    suspend fun insertRr(record: RrRecord): Long = withContext(io) { rrDao.insert(record) }

    suspend fun listRr(sessionId: Long): List<RrRecord> =
        withContext(io) { rrDao.listBySession(sessionId) }

    suspend fun listRrMs(sessionId: Long): List<Int> =
        withContext(io) { rrDao.listRrBySession(sessionId) }

    suspend fun countRr(sessionId: Long): Int = withContext(io) { rrDao.countBySession(sessionId) }

    fun observeRecentRr(sessionId: Long, limit: Int = 5): Flow<List<RrRecord>> =
        rrDao.observeRecent(sessionId, limit).flowOn(io)

    fun observeLatestRr(limit: Int = 5): Flow<List<RrRecord>> =
        rrDao.observeLatest(limit).flowOn(io)
}
