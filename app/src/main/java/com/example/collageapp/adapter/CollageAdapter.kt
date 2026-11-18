package com.example.collageapp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.collageapp.R
import com.example.collageapp.model.CollageImage
import com.example.collageapp.model.FilterType

/**
 * Muestra las fotos del collage en la cuadrícula.
 * ListAdapter + DiffUtil: solo se repintan las celdas que cambian (con animación).
 */
class CollageAdapter(
    private val onItemClick: (CollageImage) -> Unit
) : ListAdapter<CollageImage, CollageAdapter.PhotoViewHolder>(DIFF) {

    /** Id de la foto seleccionada (solo una). */
    var selectedId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            // Se refrescan todas las celdas: la seleccionada se resalta y el resto se atenúa
            notifyItemRangeChanged(0, itemCount, PAYLOAD_DECORATION)
        }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    /** Tras cada cambio de lista se renumeran las celdas (p. ej. al borrar la foto 2, la 3 pasa a ser la 2). */
    fun submit(list: List<CollageImage>, onDone: () -> Unit = {}) {
        submitList(list) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_DECORATION)
            onDone()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_collage, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = getItem(position)
        holder.image.setImageBitmap(item.current)
        holder.bindDecoration(item, position)
    }

    /** Con payload solo se actualiza el resaltado / número, sin volver a asignar el bitmap. */
    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_DECORATION) && payloads.all { it == PAYLOAD_DECORATION }) {
            holder.bindDecoration(getItem(position), position)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val container: View = view.findViewById(R.id.containerItem)
        val image: ImageView = view.findViewById(R.id.imgItem)
        private val number: TextView = view.findViewById(R.id.tvNumero)
        private val editsLabel: TextView = view.findViewById(R.id.tvEdiciones)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
            }
        }

        fun bindDecoration(item: CollageImage, position: Int) {
            val selected = item.id == selectedId
            container.setBackgroundResource(if (selected) R.drawable.item_selected_bg else R.drawable.item_normal_bg)
            number.setBackgroundResource(if (selected) R.drawable.badge_selected_bg else R.drawable.badge_bg)
            number.setTextColor(if (selected) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            number.text = (position + 1).toString()
            image.contentDescription = itemView.context.getString(R.string.desc_imagen, position + 1)
            image.alpha = if (selectedId == null || selected) 1f else 0.75f

            val label = describeEdits(item)
            editsLabel.text = label
            editsLabel.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Resumen corto de las ediciones aplicadas, p. ej. "Sepia · 90°". */
    private fun describeEdits(item: CollageImage): String {
        val e = item.edits
        val parts = mutableListOf<String>()
        when (e.filter) {
            FilterType.GRAYSCALE -> parts += "B/N"
            FilterType.SEPIA -> parts += "Sepia"
            FilterType.NONE -> Unit
        }
        if (e.rotation != 0) parts += "${e.rotation}°"
        if (e.brightness != 0) parts += "☀${if (e.brightness > 0) "+" else ""}${e.brightness}"
        if (e.saturation != 100) parts += "Sat ${e.saturation}%"
        if (e.text != null) parts += "T"
        return parts.joinToString(" · ")
    }

    companion object {
        private const val PAYLOAD_DECORATION = "decoration"

        private val DIFF = object : DiffUtil.ItemCallback<CollageImage>() {
            override fun areItemsTheSame(old: CollageImage, new: CollageImage) = old.id == new.id
            override fun areContentsTheSame(old: CollageImage, new: CollageImage) =
                old.current === new.current && old.edits == new.edits
        }
    }
}
