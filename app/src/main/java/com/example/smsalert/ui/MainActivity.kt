package com.example.smsalert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.smsalert.R
import com.example.smsalert.databinding.ActivityMainBinding
import com.example.smsalert.service.AlertService
import com.example.smsalert.service.MonitorService
import com.example.smsalert.util.PermissionHelper

/**
 * 主页：权限状态总览、规则/设置入口、后台监控开关、测试提醒，以及小米专属引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val SMS_REQ = 100
    private val NOTIF_REQ = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnStartMonitor.setOnClickListener {
            MonitorService.start(this)
            appendGuide("已尝试开启后台监控。")
            refreshStatus()
        }
        binding.btnTest.setOnClickListener {
            // 用测试数据直接触发强提醒，验证效果
            AlertService.trigger(this, "测试号码", "【测试】这是一条关键短信强提醒测试，用于验证响铃/震动/亮屏。")
        }
        binding.btnGrantSms.setOnClickListener { requestSms() }
        binding.btnGrantNotif.setOnClickListener { requestNotif() }
        binding.btnBattery.setOnClickListener {
            startActivity(PermissionHelper.batteryOptimizationIntent(this))
        }
        binding.btnOverlay.setOnClickListener {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
        }
        binding.btnAutostart.setOnClickListener {
            val mi = PermissionHelper.miuiAutoStartIntent()
            if (mi != null && mi.resolveActivity(packageManager) != null) {
                startActivity(mi)
            } else {
                appendGuide("未能自动打开自启动设置，请手动前往：设置 → 应用设置 → 权限 → 自启动，为本应用开启。")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("短信权限：")
            .append(if (PermissionHelper.hasSmsPermissions(this)) "已授权 ✅" else "未授权 ❌")
        sb.append("\n通知权限：")
            .append(if (PermissionHelper.hasNotifications(this)) "已授权 ✅" else "未授权 ❌")
        sb.append("\n电池无限制：")
            .append(if (PermissionHelper.hasIgnoreBattery(this)) "已豁免 ✅" else "受限 ❌（建议开启）")
        sb.append("\n悬浮窗：")
            .append(if (PermissionHelper.hasOverlay(this)) "已授权 ✅" else "未授权（可选）")
        binding.tvStatus.text = sb.toString()
    }

    private fun requestSms() {
        ActivityCompat.requestPermissions(this, PermissionHelper.smsPermissions(), SMS_REQ)
    }

    private fun requestNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIF_REQ
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendGuide("权限已授予。")
        } else {
            appendGuide("部分权限被拒绝，强提醒可能失效，请到系统设置手动开启。")
        }
        refreshStatus()
    }

    private fun appendGuide(text: String) {
        val cur = binding.tvGuide.text.toString()
        binding.tvGuide.text = if (cur.isBlank()) text else "$cur\n$text"
    }
}
