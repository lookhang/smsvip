package com.example.smsalert.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.smsalert.Constants
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
    private var previewPlayer: MediaPlayer? = null

    // 内置报警音：标识 → 显示名 / raw 资源
    private val alarmKeys = arrayOf(
        Constants.ALARM_DEFAULT, Constants.ALARM_SIREN, Constants.ALARM_BEEP, Constants.ALARM_PULSE
    )
    private val alarmNames by lazy {
        arrayOf(
            getString(R.string.alarm_default), getString(R.string.alarm_siren),
            getString(R.string.alarm_beep), getString(R.string.alarm_pulse)
        )
    }

    private fun alarmRawRes(key: String): Int = when (key) {
        Constants.ALARM_SIREN -> R.raw.siren
        Constants.ALARM_BEEP -> R.raw.beep
        Constants.ALARM_PULSE -> R.raw.pulse
        else -> R.raw.alarm
    }

    private fun alarmName(key: String): String {
        val idx = alarmKeys.indexOf(key).takeIf { it >= 0 } ?: 0
        return alarmNames[idx]
    }

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
        updateBuiltinAlarmText()

        binding.btnRingtone.setOnClickListener { pickRingtone() }
        binding.btnBuiltinAlarm.setOnClickListener { pickBuiltinAlarm() }
        binding.btnPreviewAlarm.setOnClickListener { previewAlarm(settings.builtinAlarm) }

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

    private fun updateBuiltinAlarmText() {
        binding.tvBuiltinAlarm.text = alarmName(settings.builtinAlarm)
    }

    private fun pickBuiltinAlarm() {
        val current = alarmKeys.indexOf(settings.builtinAlarm).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.label_builtin_alarm)
            .setSingleChoiceItems(alarmNames, current) { dialog, which ->
                val key = alarmKeys[which]
                settings.builtinAlarm = key
                updateBuiltinAlarmText()
                previewAlarm(key) // 选中即试听
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 试听内置报警音（走闹钟流，播放一遍，不循环） */
    private fun previewAlarm(key: String) {
        stopPreview()
        try {
            previewPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val afd = resources.openRawResourceFd(alarmRawRes(key))
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { stopPreview() }
                prepare()
                start()
            }
        } catch (_: Throwable) {
            Toast.makeText(this, "试听失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        try { previewPlayer?.stop() } catch (_: Throwable) { }
        try { previewPlayer?.release() } catch (_: Throwable) { }
        previewPlayer = null
    }

    override fun onStop() {
        super.onStop()
        stopPreview()
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
