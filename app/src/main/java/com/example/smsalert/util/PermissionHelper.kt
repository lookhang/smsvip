package com.example.smsalert.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限与系统设置引导助手。
 * 小米/MIUI/HyperOS 上，“强提醒”能否越过静音/勿扰，高度依赖这些授权是否到位。
 */
object PermissionHelper {

    fun hasSmsPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun smsPermissions(): Array<String> = arrayOf(
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS
    )

    fun hasNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasIgnoreBattery(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 申请电池优化豁免（允许后台常驻） */
    fun batteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /** 悬浮窗（显示在其他应用上层）设置页 */
    fun overlaySettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * 尝试跳转到小米“权限管理”应用详情页（用于系统弹窗不再出现时手动授权）。
     * 不同 MIUI/HyperOS 版本类名不同，调用方需用 resolveActivity 校验后兜底到应用详情页。
     */
    fun miuiPermissionIntent(context: Context): Intent? {
        val candidates = listOf(
            "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            "com.miui.permcenter.permission.AppPermissionsActivity",
            "com.miui.securitycenter.permission.ui.AppPermissionEditorActivity"
        )
        for (cls in candidates) {
            try {
                val intent = Intent().apply {
                    setClassName("com.miui.securitycenter", cls)
                    putExtra("extra_pkgname", context.packageName)
                }
                if (intent.resolveActivity(context.packageManager) != null) return intent
            } catch (e: Exception) {
                // try next
            }
        }
        return null
    }

    /** 系统应用详情页（通用兜底，最可靠），从这里进“权限管理”手动开启短信。 */
    fun appDetailsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * 尝试跳转到小米“自启动”管理页。
     * 不同 MIUI/HyperOS 版本包名/类名可能不同，调用方需做好兜底（手动引导）。
     */
    fun miuiAutoStartIntent(): Intent? {
        return try {
            Intent().apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
