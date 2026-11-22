package com.rrlogger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次记录会话。
 *
 * endTime == null 表示会话仍在进行中；服务被杀后靠这一条恢复。
 */
@Entity(
    tableName = "sessions",
    indices = [Index("startTime"), Index("endTime")]
)
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 会话开始时刻，Unix ms。 */
    val startTime: Long,

    /** 会话结束时刻，Unix ms；null = 进行中。 */
    val endTime: Long? = null,

    /** 标签：REST / ACTIVE / EXERCISE / 自定义。 */
    val label: String = Label.REST,

    /** 有效 RR 条数（等于 RrRecord 行数）。 */
    val totalBeats: Int = 0,

    /** 会话平均心率，由设备广播心率按拍平均。 */
    val avgHr: Int = 0,

    /** 被丢弃的条数（丢拍 + 超出生理范围 + 非 Page4 之外的无效值）。 */
    val droppedCount: Int = 0
)

/** 预设标签。打点时可提交自定义字符串。 */
object Label {
    const val REST = "REST"
    const val ACTIVE = "ACTIVE"
    const val EXERCISE = "EXERCISE"

    val PRESETS = listOf(REST, ACTIVE, EXERCISE, "CUSTOM")
}
