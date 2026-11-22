package com.rrlogger

import com.rrlogger.ui.StartPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "开始记录"可用性。
 *
 * 这里锁的是两个曾经出过问题 / 容易被改回去的条件：
 *  - 结束一段后能直接开始下一段（不需要重启 App）；
 *  - 尚未连接、但数据源可用时也允许开始（服务会自动连接）。
 */
class StartPolicyTest {

    @Test
    fun `available and idle can start`() {
        assertTrue(StartPolicy.canStart(sourceAvailable = true, recording = false))
    }

    @Test
    fun `cannot start while already recording`() {
        assertFalse(StartPolicy.canStart(sourceAvailable = true, recording = true))
    }

    @Test
    fun `cannot start when the source is unavailable`() {
        assertFalse(StartPolicy.canStart(sourceAvailable = false, recording = false))
    }

    @Test
    fun `can start again right after the previous session ended`() {
        // 结束会话后 recording=false，即便 sessionId 仍指向刚结束的那一段也应放行。
        assertTrue(StartPolicy.canStart(sourceAvailable = true, recording = false))
    }

    @Test
    fun `can start before the sensor is connected`() {
        // 连接状态不参与判定：服务会在 startSession 里主动 connect。
        assertTrue(StartPolicy.canStart(sourceAvailable = true, recording = false))
    }
}
