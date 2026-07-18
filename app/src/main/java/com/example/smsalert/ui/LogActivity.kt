package com.example.smsalert.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smsalert.R
import com.example.smsalert.data.SmsLogRepository
import com.example.smsalert.databinding.ActivityLogBinding
import com.example.smsalert.model.SmsLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提醒记录页：展示所有被 App 看到的短信（无论是否命中规则），
 * 用于验证“App 是否真的获取到了短信”以及查询历史提醒。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var repo: SmsLogRepository
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SmsLogRepository(this)
        adapter = LogAdapter()
        binding.listLog.layoutManager = LinearLayoutManager(this)
        binding.listLog.adapter = adapter

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("确定清空全部提醒记录？此操作不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    repo.clear()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        refresh()
    }

    private fun refresh() {
        val list = repo.getAll(300)
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.tvCount.text = "共 ${repo.count()} 条"
    }

    companion object {
        fun formatTime(ts: Long): String {
            return try {
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ts))
            } catch (_: Throwable) { ts.toString() }
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val items = mutableListOf<SmsLog>()

        fun submit(list: List<SmsLog>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sms_log, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
            private val tvSender: TextView = itemView.findViewById(R.id.tvLogSender)
            private val tvBody: TextView = itemView.findViewById(R.id.tvLogBody)
            private val tvTag: TextView = itemView.findViewById(R.id.tvLogTag)
            private val tvRule: TextView = itemView.findViewById(R.id.tvLogRule)

            fun bind(log: SmsLog) {
                tvTime.text = LogActivity.formatTime(log.time)
                tvSender.text = log.sender.ifBlank { "(未知号码)" }
                tvBody.text = log.body.ifBlank { "(空正文)" }
                if (log.matched) {
                    tvTag.text = if (log.alerted) "已提醒" else "命中·未提醒"
                    tvTag.setBackgroundResource(R.drawable.bg_tag_match)
                } else {
                    tvTag.text = "未命中"
                    tvTag.setBackgroundResource(R.drawable.bg_tag_normal)
                }
                tvRule.visibility = if (log.rule.isNotBlank() || log.note.isNotBlank()) View.VISIBLE else View.GONE
                tvRule.text = buildString {
                    if (log.rule.isNotBlank()) append("规则：${log.rule}")
                    if (log.note.isNotBlank()) {
                        if (isNotEmpty()) append("  ")
                        append("备注：${log.note}")
                    }
                }
            }
        }
    }
}
