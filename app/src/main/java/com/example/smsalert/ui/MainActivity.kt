package com.example.smsalert.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.smsalert.R
import com.example.smsalert.databinding.ActivityMainBinding
import com.example.smsalert.service.AlertService
import com.example.smsalert.service.MonitorService
import com.example.smsalert.util.AppLog
import com.example.smsalert.util.PermissionHelper
import java.io.File

/**
 * 主页：权限状态总览、规则/设置入口、后台监控开关、测试提醒，以及小米专属引导。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val SMS_REQ = 100
    private val NOTIF_REQ = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("MainActivity", "onCreate")
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
        binding.btnLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnAppLog.setOnClickListener {
            AppLog.i("MainActivity", "open app log dialog")
            showAppLog()
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
        // 防御性包裹：即便刷新状态抛异常，也绝不直接闪退，保证 App 可打开。
        // 注意：不再在 onResume 自动拉起 MonitorService。前台服务若未能在系统
        // 限定的 5 秒内调用 startForeground，进程会被系统直接杀死（表现为
        // “打开即闪退”），且这种延迟崩溃发生在 onResume 的 try/catch 之外，
        // catch 无法拦截。后台监控改由用户点「开启后台监控」按钮显式拉起。
        try {
            refreshStatus()
        } catch (e: Throwable) {
            AppLog.e("MainActivity", "onResume refreshStatus failed (app kept open)", e)
        }
    }

    private fun refreshStatus() {
        setStatus(
            binding.tvStatusSms,
            PermissionHelper.hasSmsPermissions(this),
            R.string.status_granted,
            R.string.status_denied
        )
        setStatus(
            binding.tvStatusNotif,
            PermissionHelper.hasNotifications(this),
            R.string.status_granted,
            R.string.status_denied
        )
        setStatus(
            binding.tvStatusBattery,
            PermissionHelper.hasIgnoreBattery(this),
            R.string.status_granted,
            R.string.status_restricted
        )
        setStatus(
            binding.tvStatusOverlay,
            PermissionHelper.hasOverlay(this),
            R.string.status_granted,
            R.string.status_optional
        )
    }

    private fun setStatus(tv: TextView, ok: Boolean, okRes: Int, noRes: Int) {
        tv.text = getString(if (ok) okRes else noRes)
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                if (ok) R.color.success else R.color.warning
            )
        )
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

    /** 显示排查日志弹窗，并提供“分享 / 复制 / 清空” */
    private fun showAppLog() {
        val log = AppLog.getLog(this)
        val tv = TextView(this).apply {
            text = log
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(40, 24, 40, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(tv)
            setPadding(24, 8, 24, 8)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setMessage(R.string.log_export_hint)
            .setView(scroll)
            .setPositiveButton(R.string.log_share) { _, _ -> shareLog() }
            .setNeutralButton(R.string.log_copy) { _, _ -> copyLog(log) }
            .setNegativeButton(R.string.log_clear) { _, _ ->
                AppLog.clear(this)
                Toast.makeText(this, "已清空日志", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 通过系统分享把日志文件发给开发者（用 FileProvider 生成 uri） */
    private fun shareLog() {
        try {
            val exportFile = File(filesDir, "app_log_export.txt")
            exportFile.writeText(AppLog.getLog(this))
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", exportFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log_share)))
        } catch (e: Throwable) {
            AppLog.e("MainActivity", "shareLog failed", e)
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLog(log: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("sms_alert_log", log))
            Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            AppLog.e("MainActivity", "copyLog failed", e)
        }
    }
}
