package com.rrlogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    /** 未结束会话（endTime IS NULL）。服务重启恢复用。 */
    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun findUnfinished(): Session?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: Long): Session?

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun observeAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    suspend fun findAll(): List<Session>

    /** 计数/心跳式更新，避免整行覆盖造成竞态。 */
    @Query(
        "UPDATE sessions SET totalBeats = :totalBeats, avgHr = :avgHr, " +
            "droppedCount = :droppedCount WHERE id = :id"
    )
    suspend fun updateStats(id: Long, totalBeats: Int, avgHr: Int, droppedCount: Int)

    @Query("UPDATE sessions SET endTime = :endTime, totalBeats = :totalBeats, " +
        "avgHr = :avgHr, droppedCount = :droppedCount WHERE id = :id")
    suspend fun finish(
        id: Long,
        endTime: Long,
        totalBeats: Int,
        avgHr: Int,
        droppedCount: Int
    )

    @Query("UPDATE sessions SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RrRecordDao {

    @Insert
    suspend fun insert(record: RrRecord): Long

    @Insert
    suspend fun insertAll(records: List<RrRecord>)

    /** 按接收时间升序，导出用。 */
    @Query("SELECT * FROM rr_records WHERE sessionId = :sessionId ORDER BY timestampMs ASC, id ASC")
    suspend fun listBySession(sessionId: Long): List<RrRecord>

    /** 只要 rrMs，导出用（省内存）。 */
    @Query("SELECT rrMs FROM rr_records WHERE sessionId = :sessionId ORDER BY timestampMs ASC, id ASC")
    suspend fun listRrBySession(sessionId: Long): List<Int>

    @Query("SELECT COUNT(*) FROM rr_records WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: Long): Int

    @Query("SELECT * FROM rr_records WHERE sessionId = :sessionId " +
        "ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeRecent(sessionId: Long, limit: Int): Flow<List<RrRecord>>

    @Query("SELECT * FROM rr_records ORDER BY timestampMs DESC, id DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<RrRecord>>

    @Query("DELETE FROM rr_records WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
