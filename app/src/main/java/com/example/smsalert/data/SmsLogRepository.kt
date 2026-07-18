package com.example.smsalert.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.smsalert.Constants
import com.example.smsalert.model.SmsLog
import com.example.smsalert.util.AppLog

/**
 * 短信监测记录仓库（SQLite）。
 *
 * 所有被 App 看到的短信都会写入，无论是否命中规则：
 * - 命中规则 → matched=true、rule=命中规则描述、alerted=是否触发了强提醒；
 * - 未命中 → matched=false；
 * - 读取失败（如权限不足）→ note 记录原因。
 *
 * 这样既能“检验 App 是否真的获取到短信”，也能作为“提醒记录”查询历史。
 */
class SmsLogRepository(context: Context) {

    private val dbHelper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TIME INTEGER NOT NULL,
                    $COL_SENDER TEXT NOT NULL DEFAULT '',
                    $COL_BODY TEXT NOT NULL DEFAULT '',
                    $COL_MATCHED INTEGER NOT NULL DEFAULT 0,
                    $COL_RULE TEXT NOT NULL DEFAULT '',
                    $COL_ALERTED INTEGER NOT NULL DEFAULT 0,
                    $COL_NOTE TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private fun db(): SQLiteDatabase = dbHelper.writableDatabase

    fun insert(log: SmsLog): Long {
        val v = ContentValues().apply {
            put(COL_TIME, log.time)
            put(COL_SENDER, log.sender)
            put(COL_BODY, log.body)
            put(COL_MATCHED, if (log.matched) 1 else 0)
            put(COL_RULE, log.rule)
            put(COL_ALERTED, if (log.alerted) 1 else 0)
            put(COL_NOTE, log.note)
        }
        return try {
            db().insert(TABLE, null, v)
        } catch (e: Exception) {
            AppLog.e("SmsLogRepo", "insert failed", e)
            -1L
        }
    }

    /** 按时间倒序返回最近 limit 条（默认 200） */
    fun getAll(limit: Int = 200): List<SmsLog> {
        val list = mutableListOf<SmsLog>()
        try {
            db().query(
                TABLE, null, null, null, null, null,
                "$COL_TIME DESC", limit.coerceAtLeast(1).toString()
            ).use { c ->
                val iId = c.getColumnIndex(COL_ID)
                val iTime = c.getColumnIndex(COL_TIME)
                val iSender = c.getColumnIndex(COL_SENDER)
                val iBody = c.getColumnIndex(COL_BODY)
                val iMatched = c.getColumnIndex(COL_MATCHED)
                val iRule = c.getColumnIndex(COL_RULE)
                val iAlerted = c.getColumnIndex(COL_ALERTED)
                val iNote = c.getColumnIndex(COL_NOTE)
                while (c.moveToNext()) {
                    list.add(
                        SmsLog(
                            id = if (iId >= 0) c.getLong(iId) else 0L,
                            time = if (iTime >= 0) c.getLong(iTime) else 0L,
                            sender = if (iSender >= 0) c.getString(iSender) ?: "" else "",
                            body = if (iBody >= 0) c.getString(iBody) ?: "" else "",
                            matched = iMatched >= 0 && c.getInt(iMatched) == 1,
                            rule = if (iRule >= 0) c.getString(iRule) ?: "" else "",
                            alerted = iAlerted >= 0 && c.getInt(iAlerted) == 1,
                            note = if (iNote >= 0) c.getString(iNote) ?: "" else ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e("SmsLogRepo", "getAll failed", e)
            // 查询失败返回空
        }
        return list
    }

    fun count(): Int {
        return try {
            db().rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (e: Exception) {
            AppLog.e("SmsLogRepo", "count failed", e)
            0
        }
    }

    /** 仅保留最近 keep 条，避免无限增长 */
    fun trim(keep: Int = 500) {
        try {
            db().execSQL(
                "DELETE FROM $TABLE WHERE $COL_ID NOT IN " +
                    "(SELECT $COL_ID FROM $TABLE ORDER BY $COL_TIME DESC LIMIT $keep)"
            )
        } catch (e: Exception) {
            AppLog.e("SmsLogRepo", "trim failed", e)
        }
    }

    fun clear() {
        try {
            db().execSQL("DELETE FROM $TABLE")
        } catch (e: Exception) {
            AppLog.e("SmsLogRepo", "clear failed", e)
        }
    }

    companion object {
        private const val DB_NAME = "sms_alert_log.db"
        private const val DB_VERSION = 1
        private const val TABLE = "sms_log"
        private const val COL_ID = "id"
        private const val COL_TIME = "time"
        private const val COL_SENDER = "sender"
        private const val COL_BODY = "body"
        private const val COL_MATCHED = "matched"
        private const val COL_RULE = "rule"
        private const val COL_ALERTED = "alerted"
        private const val COL_NOTE = "note"
    }
}
