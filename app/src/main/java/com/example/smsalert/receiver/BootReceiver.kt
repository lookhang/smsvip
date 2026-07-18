package com.example.smsalert.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.smsalert.service.MonitorService

/**
 * 开机自启：拉起后台监控服务，保证重启后仍能接收关键短信。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MonitorService.start(context)
        }
    }
}
