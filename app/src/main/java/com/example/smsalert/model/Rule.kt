package com.example.smsalert.model

/**
 * 规则类型：
 * - KEYWORD：短信正文包含该关键词即触发
 * - SENDER：发件号码包含（或结尾匹配）该号码即触发
 */
enum class RuleType { KEYWORD, SENDER }

/**
 * 单条提醒规则。
 *
 * @param id      唯一标识（用于增删改）
 * @param type    规则类型
 * @param value   关键词或号码
 * @param enabled 是否启用
 */
data class Rule(
    val id: String,
    val type: RuleType,
    val value: String,
    val enabled: Boolean
)
