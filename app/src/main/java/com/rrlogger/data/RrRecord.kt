package com.rrlogger.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条有效 R-R 间期。
 *
 * 硬约束：rrMs 只能来自 heartBeatEventTime 差值换算，
 * timestampMs 仅是"接收时刻"记录列，绝不参与 RR 计算。
 */
@Entity(
    tableName = "rr_records",
    foreignKeys = [
        ForeignKey(
            entity = Session::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("timestampMs"), Index(value = ["sessionId", "timestampMs"])]
)
data class RrRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val sessionId: Long,

    /** 接收时刻，Unix ms。仅作记录，不用于 RR 反算。 */
    val timestampMs: Long,

    /** R-R 间期，毫秒。来自 Page 4 heartBeatEventTime 差值。 */
    val rrMs: Int,

    /** 该拍设备广播的瞬时心率（bpm）。 */
    val heartRate: Int
)
