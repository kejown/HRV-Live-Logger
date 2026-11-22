package com.rrlogger.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.rrlogger.data.RrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出 Kubios HRV 兼容的 RR 文本。
 *
 * Kubios 的 "RR interval text file" 导入要求：
 *   - 纯文本，每行一个 RR 间期
 *   - 单位毫秒，整数
 *   - 不允许表头、单位、逗号（逗号会触发 CSV 解析分歧）
 *   - 换行 LF 即可，Kubios 同时接受 CRLF
 *
 * 因此这里只写 `rrMs\n`。标签/会话信息不进文件，放在文件名里。
 *
 * 数据按 [PAGE_SIZE] 分页读取、逐行写进缓冲流：8 小时记录约 29k 行（≈200KB），
 * 旧实现会先拼一个完整 String 再整体写出，分页后峰值内存与记录时长无关。
 */
class RrExporter(
    private val context: Context,
    private val repo: RrRepository
) {

    data class Result(
        val success: Boolean,
        val uri: Uri?,
        val displayPath: String,
        val beatCount: Int,
        val message: String
    )

    private class Target(val uri: Uri?, val displayPath: String, val written: Int)

    /**
     * @param sessionId 会话 id
     * @param label     文件名里的标签
     * @param startMs   会话开始时间，用于文件名时间戳
     */
    suspend fun export(
        sessionId: Long,
        label: String,
        startMs: Long
    ): Result = withContext(Dispatchers.IO) {
        val total = repo.countRr(sessionId)
        if (total <= 0) {
            return@withContext Result(
                success = false, uri = null, displayPath = "",
                beatCount = 0, message = "该会话没有可导出的 RR 数据"
            )
        }

        val fileName = buildFileName(startMs, label)

        try {
            val target = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    writeViaMediaStore(fileName, sessionId)
                else -> writeLegacy(fileName, sessionId)
            }
            Result(
                success = true,
                uri = target.uri,
                displayPath = target.displayPath,
                beatCount = target.written,
                message = "已导出 ${target.written} 拍到 ${target.displayPath}"
            )
        } catch (t: Throwable) {
            Result(
                success = false, uri = null, displayPath = "",
                beatCount = 0, message = "导出失败：${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    /** rr_yyyyMMdd_HHmm_<label>.txt —— 标签里的非法字符替换为下划线。 */
    fun buildFileName(startMs: Long, label: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(startMs))
        val safeLabel = label.trim()
            .ifEmpty { "RR" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(24)
        return "rr_${ts}_$safeLabel.txt"
    }

    // ---------------- API 29+ ----------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun writeViaMediaStore(fileName: String, sessionId: Long): Target {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // 同名文件自动追加 (1)(2)…
        var finalName = fileName
        var index = 1
        while (existsInMediaStore(collection, finalName)) {
            val base = fileName.substringBeforeLast('.')
            finalName = "$base(${index}).txt"
            index++
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + File.separator + SUB_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore 创建文件失败")

        val written = try {
            resolver.openOutputStream(uri, "w")?.use { os ->
                os.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writeRrLines(writer, sessionId)
                }
            } ?: throw IllegalStateException("无法打开输出流")
        } catch (t: Throwable) {
            // 失败时删掉 IS_PENDING 的残件，避免在 Download/RRLogger 里留下 0 字节文件。
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return Target(uri, "Download/$SUB_DIR/$finalName", written)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun existsInMediaStore(collection: Uri, name: String): Boolean {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(name, "%$SUB_DIR%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            return c.count > 0
        }
        return false
    }

    // ---------------- API 26-28 ----------------

    @Suppress("DEPRECATION")
    private suspend fun writeLegacy(fileName: String, sessionId: Long): Target {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val dir = File(downloads, SUB_DIR).apply { if (!exists()) mkdirs() }

        var target = File(dir, fileName)
        var index = 1
        while (target.exists()) {
            val base = fileName.substringBeforeLast('.')
            target = File(dir, "$base(${index}).txt")
            index++
        }
        val written = target.bufferedWriter(Charsets.UTF_8).use { writer ->
            writeRrLines(writer, sessionId)
        }
        return Target(Uri.fromFile(target), target.absolutePath, written)
    }

    // ---------------- 公共写循环 ----------------

    /**
     * 分页读取并逐行写出，返回实际写出的行数。
     *
     * 不在导出开始时快照整段会话：会话仍在记录时，分页能自然导出到当前为止的数据。
     */
    private suspend fun writeRrLines(writer: Writer, sessionId: Long): Int {
        var offset = 0
        var written = 0
        while (true) {
            val page = repo.pageRrMs(sessionId, offset, PAGE_SIZE)
            if (page.isEmpty()) break
            for (rr in page) {
                writer.write(rr.toString())
                writer.append('\n')
            }
            written += page.size
            offset += page.size
            if (page.size < PAGE_SIZE) break
        }
        writer.flush()
        return written
    }

    private companion object {
        const val SUB_DIR = "RRLogger"

        /** 每页 2000 条 ≈ 8KB 的 int 列表，兼顾查询次数与内存。 */
        const val PAGE_SIZE = 2_000
    }
}
