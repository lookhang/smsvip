package com.example.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import com.example.smsalert.service.AlertDispatch

/**
 * 主触发通道：监听系统 SMS_RECEIVED 广播。
 * 该广播由系统以 BROADCAST_SMS 权限发送，因此 Manifest 中声明了对应 permission。
 * 优先级设为 1000，尽量先于其他应用拿到短信。
 *
 * 实际的处理/记录/提醒统一交给 AlertDispatch，避免与轮询通道重复触发。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return
        val bundle = intent.extras ?: return
        val pdus = bundle["pdus"] as? Array<*> ?: return
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bundle["format"] as? String
        } else null

        for (pdu in pdus) {
            try {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
                val sender = sms.displayOriginatingAddress ?: ""
                val body = sms.messageBody ?: ""
                AlertDispatch.handle(context, sender, body)
            } catch (e: Exception) {
                // 单条解析失败不应中断整体
            }
        }
    }
}
