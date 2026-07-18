package com.example.smsalert.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.example.smsalert.R
import com.example.smsalert.data.RulesRepository
import com.example.smsalert.databinding.ActivityRulesBinding
import com.example.smsalert.model.Rule
import com.example.smsalert.model.RuleType

/**
 * 规则管理：新增关键词 / 号码规则，列表展示，点击切换启用，长按删除。
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding
    private lateinit var repo: RulesRepository
    private lateinit var adapter: android.widget.ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = RulesRepository(this)
        adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.listRules.adapter = adapter

        binding.btnAddKeyword.setOnClickListener { showAdd(RuleType.KEYWORD) }
        binding.btnAddSender.setOnClickListener { showAdd(RuleType.SENDER) }

        binding.listRules.setOnItemClickListener { _, _, pos, _ ->
            val rule = currentRules[pos]
            repo.toggle(rule.id, !rule.enabled)
            refresh()
        }
        binding.listRules.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val rule = currentRules[pos]
            AlertDialog.Builder(this)
                .setTitle("删除规则")
                .setMessage("确认删除：\n${describe(rule)}？")
                .setPositiveButton("删除") { _, _ ->
                    repo.remove(rule.id)
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        refresh()
    }

    private var currentRules: List<Rule> = emptyList()

    private fun refresh() {
        currentRules = repo.getAll()
        adapter.clear()
        currentRules.forEach { adapter.add(describe(it)) }
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility =
            if (currentRules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun describe(rule: Rule): String {
        val type = when (rule.type) {
            RuleType.KEYWORD -> "关键词"
            RuleType.SENDER -> "号码"
        }
        val state = if (rule.enabled) "启用" else "停用"
        return "[$state][$type] ${rule.value}"
    }

    private fun showAdd(type: RuleType) {
        val input = EditText(this).apply {
            hint = if (type == RuleType.KEYWORD) "输入要匹配的关键词" else "输入号码片段（如 10086）"
        }
        val title = if (type == RuleType.KEYWORD) "新增关键词规则" else "新增号码规则"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    repo.add(type, v)
                    refresh()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
