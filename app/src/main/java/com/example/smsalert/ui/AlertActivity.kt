package com.example.smsalert.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smsalert.Constants
import com.example.smsalert.R
import com.example.smsalert.data.SettingsRepository
import com.example.smsalert.databinding.ActivityAlertBinding
import com.example.smsalert.service.AlertService

/**
 * 强提醒全屏界面。
 *
 * - setShowWhenLocked(true) + setTurnScreenOn(true)：在锁屏之上弹出并点亮屏幕；
 * - FLAG_KEEP_SCREEN_ON：保持亮屏直到用户关闭；
 * - 背景红色闪烁（可配置），进一步强化“强提醒”；
 * - 屏蔽返回键，必须点“关闭提醒”才结束 —— 直到用户主动关闭。
 */
class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding
    private lateinit var settings: SettingsRepository
    private var flashAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上层 + 点亮屏幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 保持亮屏 + 解锁键盘锁
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)

        val sender = intent.getStringExtra(Constants.EXTRA_SENDER) ?: ""
        val body = intent.getStringExtra(Constants.EXTRA_BODY) ?: ""
        binding.tvSender.text = sender
        binding.tvBody.text = body

        binding.btnDismiss.setOnClickListener {
            val stop = android.content.Intent(this, AlertService::class.java).apply {
                action = Constants.ACTION_STOP_ALERT
            }
            startService(stop)
            finish()
        }

        if (settings.screenFlash) startFlash()
    }

    /** 红色 ↔ 暗红 背景呼吸闪烁，视觉上“越强越好” */
    private fun startFlash() {
        val red = ContextCompat.getColor(this, R.color.alert_red)
        val dark = ContextCompat.getColor(this, R.color.alert_bg)
        flashAnimator = ValueAnimator.ofObject(ArgbEvaluator(), red, dark).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                binding.root.setBackgroundColor(it.animatedValue as Int)
            }
            start()
        }
    }

    override fun onBackPressed() {
        // 忽略返回，强制用户通过“关闭提醒”结束
        Toast.makeText(this, "请点击“关闭提醒”结束", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        flashAnimator?.cancel()
        flashAnimator = null
        super.onDestroy()
    }
}
