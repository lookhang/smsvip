package com.example.smsalert.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smsalert.R
import com.example.smsalert.data.SettingsRepository
import com.example.smsalert.databinding.ActivitySettingsBinding

/**
 * 高级设置：提醒铃声、重复保活间隔、最大音量/震动/闪屏开关。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private val RINGTONE_REQ = 300

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsRepository(this)

        // 初始化界面
        binding.etInterval.setText(settings.repeatIntervalSec.toString())
        binding.cbMaxVolume.isChecked = settings.maxVolume
        binding.cbVibrate.isChecked = settings.vibrate
        binding.cbFlash.isChecked = settings.screenFlash
        binding.cbTts.isChecked = settings.ttsEnabled
        binding.cbTtsBody.isChecked = settings.ttsReadBody
        updateRingtoneText()

        binding.btnRingtone.setOnClickListener { pickRingtone() }

        binding.cbMaxVolume.setOnCheckedChangeListener { _, c -> settings.maxVolume = c }
        binding.cbVibrate.setOnCheckedChangeListener { _, c -> settings.vibrate = c }
        binding.cbFlash.setOnCheckedChangeListener { _, c -> settings.screenFlash = c }
        binding.cbTts.setOnCheckedChangeListener { _, c -> settings.ttsEnabled = c }
        binding.cbTtsBody.setOnCheckedChangeListener { _, c -> settings.ttsReadBody = c }

        binding.btnSave.setOnClickListener {
            val v = binding.etInterval.text.toString().toIntOrNull()
            if (v == null || v < 5) {
                Toast.makeText(this, "重复间隔需为不小于 5 的整数（秒）", Toast.LENGTH_SHORT).show()
            } else {
                settings.repeatIntervalSec = v
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun updateRingtoneText() {
        val uriStr = settings.ringtoneUri ?: settings.defaultRingtoneUri()
        if (uriStr.isNullOrBlank()) {
            binding.tvRingtone.text = "内置报警音（系统无默认铃声）"
            return
        }
        val ringtone = RingtoneManager.getRingtone(this, Uri.parse(uriStr))
        val name = ringtone?.getTitle(this) ?: "内置报警音"
        binding.tvRingtone.text = name
    }

    private fun pickRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            val cur = settings.ringtoneUri?.let { Uri.parse(it) }
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
        }
        startActivityForResult(intent, RINGTONE_REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_REQ && resultCode == RESULT_OK) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            settings.ringtoneUri = uri?.toString()
            updateRingtoneText()
        }
    }
}
