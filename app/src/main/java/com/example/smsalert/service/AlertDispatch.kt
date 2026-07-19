package com.example.smsalert.service

import android.content.Context
import android.content.SharedPreferences
import com.example.smsalert.data.RulesRepository
import com.example.smsalert.data.SmsLogRepository
import com.example.smsalert.model.RuleType
import com.example.smsalert.model.SmsLog
import org.json.JSONArray
import org.json.JSONObject
import com.example.smsalert.util.AppLog

/**
 * 统一的“短信处理/派发”入口：广播通道（SmsReceiver）与轮询通道（MonitorService）都调用本对象，
 * 避免两条通道对同一短信重复记录或重复触发强提醒。
 *
 * 职责：
 * 1) 写入 SmsLog（无论是否命中规则，都记录，便于验证 App 是否获取到短信）；
 * 2) 命中规则时触发强提醒，但 2 分钟内同一短信（按 发件人|正文 签名）只提醒一次。
 *
 * 去重说明：广播与轮询是不同进程/入口，均通过本对象集中处理，并以签名 + 时间窗
 * （记录 10 分钟、提醒 2 分钟）在 SharedPreferences 中跨通道去重。
 */
object AlertDispatch {

    private const val PREFS = "alert_guard_prefs"
    private const val KEY_LOG = "recent_logs"
    private const val KEY_ALERT = "recent_alerts"
    private const val LOG_WINDOW_MS = 10 * 60 * 1000L
    private const val ALERT_WINDOW_MS = 2 * 60 * 1000L
    private const val MAX_ENTRIES = 200

    fun handle(context: Context, sender: String, body: String) {
        val s = sig(sender, body)
        if (recentlyLogged(context, s)) {
            AppLog.d("AlertDispatch", "skip dup from=$sender")
            return // 已由任一通道记录过，跳过避免重复
        }

        val rules = RulesRepository(context)
        val logRepo = SmsLogRepository(context)
        // 收集**所有**命中规则（可能同时命中多个关键词/号码）
        val matchedRules = runCatching { rules.matchesAll(sender, body) }.getOrDefault(emptyList())
        val matched = matchedRules.isNotEmpty()

        // 合并命中的关键词与号码（去重、保序）
        val keywordVals = matchedRules.filter { it.type == RuleType.KEYWORD }.map { it.value }.distinct()
        val senderVals = matchedRules.filter { it.type == RuleType.SENDER }.map { it.value }.distinct()
        // 播报优先用关键词（更具描述性），否则用号码
        val speakType = if (keywordVals.isNotEmpty()) RuleType.KEYWORD.name else if (senderVals.isNotEmpty()) RuleType.SENDER.name else ""
        val speakValue = if (keywordVals.isNotEmpty()) keywordVals.joinToString("、") else senderVals.joinToString("、")
        AppLog.i("AlertDispatch", "handle matched=$matched from=$sender hits=${matchedRules.size} kw=$keywordVals")

        // 关键：同一短信（含命中多个关键词、双通道并发）只报警一次 —— 用原子 claim
        val alerted = if (matched && claimAlert(context, s)) {
            val ok = runCatching { AlertService.trigger(context, sender, body, speakValue, speakType) }.isSuccess
            if (!ok) {
                releaseAlertClaim(context, s) // 触发失败则撤销 claim，允许后续重试
                AppLog.e("AlertDispatch", "AlertService.trigger failed for from=$sender", null)
            }
            ok
        } else false

        val ruleDesc = buildString {
            if (keywordVals.isNotEmpty()) append("[关键词] ${keywordVals.joinToString("、")}")
            if (senderVals.isNotEmpty()) {
                if (isNotEmpty()) append("  ")
                append("[号码] ${senderVals.joinToString("、")}")
            }
        }
        logRepo.insert(
            SmsLog(
                time = System.currentTimeMillis(),
                sender = sender,
                body = body.take(400),
                matched = matched,
                rule = ruleDesc,
                alerted = alerted
            )
        )
        logRepo.trim()
        markLogged(context, s)
    }

    private fun sig(sender: String, body: String): String =
        "$sender|${body.take(160)}".hashCode().toString()

    @Synchronized
    private fun recentlyLogged(context: Context, s: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prune(prefs, KEY_LOG, LOG_WINDOW_MS)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("s") == s) {
                writeArr(prefs, KEY_LOG, arr)
                return true
            }
        }
        writeArr(prefs, KEY_LOG, arr)
        return false
    }

    /**
     * 原子“认领报警”：检查 + 标记在同一同步块内完成。
     * 仅第一个调用者（对某短信签名）返回 true，其余（含双通道并发、命中多个关键词）返回 false，
     * 从而保证同一短信只触发一次强提醒。
     */
    @Synchronized
    private fun claimAlert(context: Context, s: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prune(prefs, KEY_ALERT, ALERT_WINDOW_MS)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("s") == s) {
                writeArr(prefs, KEY_ALERT, arr)
                return false // 已被认领，跳过
            }
        }
        arr.put(JSONObject().apply { put("s", s); put("t", System.currentTimeMillis()) })
        while (arr.length() > MAX_ENTRIES) arr.remove(0)
        writeArr(prefs, KEY_ALERT, arr)
        return true
    }

    /** 撤销报警认领（触发失败时调用，允许后续通道重试） */
    @Synchronized
    private fun releaseAlertClaim(context: Context, s: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prune(prefs, KEY_ALERT, ALERT_WINDOW_MS)
        val remove = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getString("s") == s) remove.add(i)
        }
        for (i in remove.reversed()) arr.remove(i)
        writeArr(prefs, KEY_ALERT, arr)
    }

    @Synchronized
    private fun markLogged(context: Context, s: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = prune(prefs, KEY_LOG, LOG_WINDOW_MS)
        arr.put(JSONObject().apply { put("s", s); put("t", System.currentTimeMillis()) })
        while (arr.length() > MAX_ENTRIES) arr.remove(0)
        writeArr(prefs, KEY_LOG, arr)
    }

    private fun prune(prefs: SharedPreferences, key: String, window: Long): JSONArray {
        val arr = try { JSONArray(prefs.getString(key, "[]") ?: "[]") }
        catch (e: Exception) { JSONArray() }
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (now - o.getLong("t") > window) toRemove.add(i)
        }
        for (i in toRemove.reversed()) arr.remove(i)
        return arr
    }

    private fun writeArr(prefs: SharedPreferences, key: String, arr: JSONArray) {
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
