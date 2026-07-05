package com.trinityvirtual.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trinityvirtual.databinding.ItemModuleBinding
import com.trinityvirtual.model.TrinityModule

class ModuleAdapter(
    private val onToggle: (TrinityModule, Boolean) -> Unit,
    private val onDelete: (TrinityModule) -> Unit
) : ListAdapter<TrinityModule, ModuleAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemModuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val module = getItem(position)
        with(holder.binding) {
            tvModuleName.text = module.name
            tvModuleVersion.text = "v${module.version} by ${module.author}"
            tvModuleDesc.text = module.description
            tvModuleType.text = module.type.name
            switchModule.isChecked = module.isEnabled
            switchModule.setOnCheckedChangeListener { _, checked -> onToggle(module, checked) }
            btnDeleteModule.setOnClickListener { onDelete(module) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TrinityModule>() {
            override fun areItemsTheSame(a: TrinityModule, b: TrinityModule) = a.id == b.id
            override fun areContentsTheSame(a: TrinityModule, b: TrinityModule) = a == b
        }
    }
}
