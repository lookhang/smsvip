package com.example.smsalert.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        val perms = PermissionHelper.smsPermissions()
        if (PermissionHelper.hasSmsPermissions(this)) {
            appendGuide("短信权限已授权。")
            return
        }
        // 若某权限既未授权、又 shouldShowRequestPermissionRationale=false，
        // 说明用户曾勾选“不再询问”或 MIUI 已接管并不再弹窗 → 直接跳设置页手动开。
        val blocked = perms.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (blocked) {
            val mi = PermissionHelper.miuiPermissionIntent(this)
            if (mi != null) {
                appendGuide("系统弹窗已不再出现，正在打开小米「权限管理」，请将「短信」设为允许。")
                startActivity(mi)
            } else {
                appendGuide("正在打开系统应用设置，请进入「权限」→「短信」设为允许。")
                startActivity(PermissionHelper.appDetailsIntent(this))
            }
        } else {
            ActivityCompat.requestPermissions(this, perms, SMS_REQ)
        }
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
            appendGuide("短信权限已授予。")
        } else {
            appendGuide(
                "短信权限被拒绝。小米手机请前往：设置 → 应用设置 → 权限管理 → 关键短信强提醒 → " +
                "短信 → 设为「允许」（含接收与读取）；或把本应用设为默认短信应用。强提醒依赖此权限。"
            )
        }
        refreshStatus()
    }

    private fun appendGuide(text: String) {
        val cur = binding.tvGuide.text.toString()
        binding.tvGuide.text = if (cur.isBlank()) text else "$cur\n$text"
    }
}
