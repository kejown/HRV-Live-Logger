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
import java.io.OutputStreamWriter
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
        val rrList = repo.listRrMs(sessionId)
        if (rrList.isEmpty()) {
            return@withContext Result(
                success = false, uri = null, displayPath = "",
                beatCount = 0, message = "该会话没有可导出的 RR 数据"
            )
        }

        val fileName = buildFileName(startMs, label)
        val body = buildString(rrList.size * 6) {
            for (rr in rrList) {
                append(rr)
                append('\n')
            }
        }

        try {
            val (uri, path) = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    writeViaMediaStore(fileName, body)
                else -> writeLegacy(fileName, body)
            }
            Result(
                success = true,
                uri = uri,
                displayPath = path,
                beatCount = rrList.size,
                message = "已导出 ${rrList.size} 拍到 $path"
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
    private fun writeViaMediaStore(fileName: String, body: String): Pair<Uri?, String> {
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

        resolver.openOutputStream(uri, "w")?.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                // 不用 bufferedWriter 的自动换行差异，直接写
                w.write(body)
                w.flush()
            }
        } ?: throw IllegalStateException("无法打开输出流")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return uri to "Download/$SUB_DIR/$finalName"
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
    private fun writeLegacy(fileName: String, body: String): Pair<Uri?, String> {
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
        target.writeText(body, Charsets.UTF_8)
        return Uri.fromFile(target) to target.absolutePath
    }

    private companion object {
        const val SUB_DIR = "RRLogger"
    }
}
