package com.viscouspot.gitsync.ui.adapter

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.viscouspot.gitsync.R

class ManualSyncItemAdapter (
    private val context: Context,
    private val filePaths: List<String>,
    private val selectedFilePaths: MutableList<String>,
    private val selectFile: (filePath: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class BasicViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val manualSyncItem: ConstraintLayout = view.findViewById(R.id.manualSyncItem)
        val filePath: TextView = view.findViewById(R.id.filePath)
        val fileName: TextView = view.findViewById(R.id.fileName)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder as BasicViewHolder
        val filePath = filePaths[position]
        holder.filePath.text = if (filePath.contains("/")) filePath.replaceAfterLast("/", "") else ""
        holder.fileName.text = filePath.replaceBeforeLast("/", "").replace("/", "")
        if (selectedFilePaths.contains(filePaths[position])) {
            holder.manualSyncItem.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.additions))
        } else {
            holder.manualSyncItem.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary))
        }

        holder.manualSyncItem.setOnClickListener {
            selectFile.invoke(filePath)
            notifyItemRangeChanged(position, 1)
        }
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.manual_sync_item, viewGroup, false)
        return BasicViewHolder(view)
    }

    override fun getItemCount() = filePaths.size
}