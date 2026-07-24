package com.telplayer.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.telplayer.app.databinding.ItemTrackBinding

class TrackAdapter(
    private val onPlay: (Int) -> Unit,
    private val onAdd: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    var items: List<Track> = listOf()
    var current = -1

    inner class VH(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = items[i]
        h.b.idx.text = if (i == current) "▶" else fa((i + 1).toString())
        h.b.title.text = t.title
        h.b.sub.text = "${t.channel} • " + if (t.durationMs > 0) fmt(t.durationMs) else "—"
        h.b.root.setOnClickListener { onPlay(h.bindingAdapterPosition) }
        h.b.btnAdd.setOnClickListener { onAdd(h.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size

    private fun fa(s: String) = s.map { if (it.isDigit()) "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("")
    private fun fmt(ms: Long): String { val s = ms / 1000; return fa("${s / 60}:${(s % 60).toString().padStart(2, '0')}") }
}
