package com.example.smsalert.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量本地日志：所有关键路径都写到这里，崩溃也会写入。
 * 用途：出现“打开闪退 / 测试没声音 / 收不到短信”等无法本地复现的问题时，
 * 用户可在主页点「排查日志」→「分享」，把日志发回，便于定位根因。
 *
 * 实现要点：
 * - 环形缓冲，最多保留 MAX_LINES 行，避免无限增长；
 * - 每次写入都同步 flush 到文件（进程崩溃前已落盘）；
 * - 全局 UncaughtExceptionHandler 会把任何未捕获异常堆栈写入本日志，
 *   因此即便 App 闪退，上一次的崩溃堆栈也会留存在文件里。
 */
object AppLog {

    private const val FILE_NAME = "app_log.txt"
    private const val MAX_LINES = 600
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    @Volatile
    private var appContext: Context? = null

    fun setContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    @Synchronized
    fun d(tag: String, msg: String) = write("D", tag, msg)

    @Synchronized
    fun i(tag: String, msg: String) = write("I", tag, msg)

    @Synchronized
    fun w(tag: String, msg: String) = write("W", tag, msg)

    @Synchronized
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val sb = StringBuilder(msg)
        t?.let {
            try {
                sb.append("\n").append(Log.getStackTraceString(it))
            } catch (_: Throwable) {
            }
        }
        write("E", tag, sb.toString())
    }

    private fun write(level: String, tag: String, msg: String) {
        val ctx = appContext ?: return
        val line = "${sdf.format(Date())} $level/$tag: $msg"
        // 内部私有目录：App 内「排查日志」可读
        appendLine(File(ctx.filesDir, FILE_NAME), line)
        // 外部存储：App 若闪退进不去，用户仍可在
        // /sdcard/Android/data/com.example.smsalert/files/app_log.txt 取到日志
        try {
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) appendLine(File(ext, FILE_NAME), line)
        } catch (_: Throwable) { }
    }

    private fun appendLine(file: File, line: String) {
        try {
            val existing = if (file.exists()) file.readLines() else emptyList()
            val all = existing + line
            val trimmed = if (all.size > MAX_LINES) all.takeLast(MAX_LINES) else all
            file.bufferedWriter().use { w ->
                for (l in trimmed) {
                    w.write(l)
                    w.write("\n")
                }
            }
        } catch (_: Throwable) {
            // 日志自身失败不能影响业务
        }
    }

    @Synchronized
    fun getLog(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists() || file.length() == 0L) "（暂无日志）" else file.readText()
        } catch (e: Throwable) {
            "（读取日志失败：${e.message}）"
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
