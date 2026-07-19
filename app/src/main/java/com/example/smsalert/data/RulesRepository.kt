package com.example.smsalert.data

import android.content.Context
import com.example.smsalert.Constants
import com.example.smsalert.model.Rule
import com.example.smsalert.model.RuleType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 规则仓库：基于 SharedPreferences + JSON 持久化，无需额外数据库依赖。
 * 提供匹配判断（关键词/号码）与增删改。
 */
class RulesRepository(context: Context) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_RULES, Context.MODE_PRIVATE)

    fun getAll(): List<Rule> {
        val json = prefs.getString(Constants.KEY_RULES_JSON, "[]") ?: "[]"
        val list = mutableListOf<Rule>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Rule(
                        id = o.getString("id"),
                        type = RuleType.valueOf(o.getString("type")),
                        value = o.getString("value"),
                        enabled = o.getBoolean("enabled")
                    )
                )
            }
        } catch (e: Exception) {
            // 解析失败则忽略，返回空列表
        }
        return list
    }

    /**
     * 判断短信是否命中任意启用规则。
     * @return 命中的规则；无命中返回 null
     */
    fun matches(sender: String, body: String): Rule? {
        return matchesAll(sender, body).firstOrNull()
    }

    /**
     * 返回短信命中的**所有**启用规则（可能同时命中多个关键词/号码）。
     * 上层据此合并展示与播报，但只触发一次强提醒。
     */
    fun matchesAll(sender: String, body: String): List<Rule> {
        val s = sender.trim()
        val b = body.lowercase()
        return getAll().filter { rule ->
            if (!rule.enabled || rule.value.isBlank()) return@filter false
            when (rule.type) {
                RuleType.KEYWORD -> b.contains(rule.value.lowercase())
                RuleType.SENDER -> {
                    val v = rule.value.trim()
                    s.contains(v) || s.endsWith(v)
                }
            }
        }
    }

    fun add(type: RuleType, value: String): Rule {
        val rule = Rule(UUID.randomUUID().toString(), type, value.trim(), true)
        val list = getAll().toMutableList()
        list.add(rule)
        save(list)
        return rule
    }

    fun remove(id: String) {
        save(getAll().filter { it.id != id })
    }

    fun toggle(id: String, enabled: Boolean) {
        save(getAll().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun save(list: List<Rule>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("type", it.type.name)
                    put("value", it.value)
                    put("enabled", it.enabled)
                }
            )
        }
        prefs.edit().putString(Constants.KEY_RULES_JSON, arr.toString()).apply()
    }
}
