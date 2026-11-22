package com.rrlogger.ui

/**
 * "开始记录"按钮的可用性判定（纯逻辑，便于单测）。
 *
 * 三个条件里刻意**不包含**"已连接"和"当前没有会话"：
 *  - 未连接也允许开始：服务在 [com.rrlogger.service.RecordingService.startSession]
 *    里会自动发起连接，断线重连本来就是它的职责；
 *  - 上一段结束后必须能立刻开始下一段：v4 的 `sessionId == null` 条件会让
 *    "开始记录"在一次 App 生命周期内永久变灰。
 */
object StartPolicy {

    fun canStart(sourceAvailable: Boolean, recording: Boolean): Boolean =
        sourceAvailable && !recording
}
