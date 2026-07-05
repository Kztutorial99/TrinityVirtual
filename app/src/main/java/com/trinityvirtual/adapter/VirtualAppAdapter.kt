package com.trinityvirtual.adapter

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trinityvirtual.databinding.ItemVirtualAppBinding
import com.trinityvirtual.model.VirtualApp
import java.io.File

class VirtualAppAdapter(
    private val onAppClick: (VirtualApp) -> Unit,
    private val onAppLongClick: (VirtualApp) -> Unit
) : ListAdapter<VirtualApp, VirtualAppAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemVirtualAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemVirtualAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        with(holder.binding) {
            tvAppName.text = app.appName
            if (app.iconPath != null && File(app.iconPath).exists()) {
                val bmp = BitmapFactory.decodeFile(app.iconPath)
                ivAppIcon.setImageBitmap(bmp)
            } else {
                ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            ivRootBadge.visibility = if (app.rootEnabled) android.view.View.VISIBLE else android.view.View.GONE
            root.setOnClickListener { onAppClick(app) }
            root.setOnLongClickListener { onAppLongClick(app); true }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VirtualApp>() {
            override fun areItemsTheSame(a: VirtualApp, b: VirtualApp) = a.id == b.id
            override fun areContentsTheSame(a: VirtualApp, b: VirtualApp) = a == b
        }
    }
}
