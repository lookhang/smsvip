package com.example.smsalert.model

/**
 * 一条短信监测记录。
 *
 * 用途：
 * 1) 验证 App 是否真的读到了短信（无论是否命中规则都会记录）；
 * 2) 作为“提醒记录”供用户查询。
 *
 * @param id        主键（自增）
 * @param time      收到时间（epoch 毫秒）
 * @param sender    发件人
 * @param body      短信正文（已截断到合适长度，便于展示与存储）
 * @param matched   是否命中任意规则
 * @param rule      命中的规则描述；未命中为空串
 * @param alerted   是否触发了强提醒
 * @param note      备注（如读取失败原因、测试标记等）
 */
data class SmsLog(
    val id: Long = 0,
    val time: Long,
    val sender: String,
    val body: String,
    val matched: Boolean,
    val rule: String,
    val alerted: Boolean,
    val note: String = ""
)
