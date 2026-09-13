package com.parentcontrol.screentime.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.parentcontrol.screentime.data.AppLimitEntity

class AppLimitAdapter(
    private val onDelete: (AppLimitEntity) -> Unit
) : RecyclerView.Adapter<AppLimitAdapter.ViewHolder>() {

    private var items: List<AppLimitEntity> = emptyList()

    fun submitList(newItems: List<AppLimitEntity>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(1001)
        val subtitle: TextView = view.findViewById(1002)
        val deleteBtn: Button = view.findViewById(1003)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(context).apply { id = 1001; textSize = 16f }
        val subtitle = TextView(context).apply { id = 1002; textSize = 13f }
        textColumn.addView(title)
        textColumn.addView(subtitle)
        val deleteBtn = Button(context).apply { id = 1003; text = "삭제" }
        row.addView(textColumn)
        row.addView(deleteBtn)
        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = "${item.appLabel} (${item.packageName})"
        holder.subtitle.text =
            "하루 ${item.dailyLimitMinutes}분 · 그레이스 ${item.gracePeriodSeconds}초"
        holder.deleteBtn.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size
}
