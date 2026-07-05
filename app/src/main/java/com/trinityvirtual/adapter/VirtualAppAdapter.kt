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

    inner class VH(val binding: ItemVirtualAppBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onAppClick(getItem(pos))
            }
            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) onAppLongClick(getItem(pos))
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemVirtualAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        with(holder.binding) {
            tvAppName.text = app.appName

            // Source type badge (APK / APKS / XAPK)
            tvSourceBadge.text = app.sourceType.uppercase()

            // Root indicator dot
            viewRootIndicator.visibility = if (app.rootEnabled) android.view.View.VISIBLE else android.view.View.GONE

            // Icon from saved path
            if (!app.iconPath.isNullOrEmpty()) {
                val iconFile = File(app.iconPath)
                if (iconFile.exists()) {
                    try {
                        val bmp = BitmapFactory.decodeFile(iconFile.absolutePath)
                        if (bmp != null) {
                            ivAppIcon.setImageBitmap(bmp)
                        } else {
                            ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                        }
                    } catch (e: Exception) {
                        ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                } else {
                    ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            } else {
                ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VirtualApp>() {
            override fun areItemsTheSame(a: VirtualApp, b: VirtualApp) = a.id == b.id
            override fun areContentsTheSame(a: VirtualApp, b: VirtualApp) =
                a.appName == b.appName &&
                a.rootEnabled == b.rootEnabled &&
                a.iconPath == b.iconPath &&
                a.sourceType == b.sourceType
        }
    }
}
