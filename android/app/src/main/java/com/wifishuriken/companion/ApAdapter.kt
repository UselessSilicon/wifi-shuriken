package com.wifishuriken.companion

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wifishuriken.companion.core.ApEntry
import com.wifishuriken.companion.databinding.ItemApBinding

class ApAdapter : ListAdapter<ApEntry, ApAdapter.Holder>(Diff) {

  class Holder(val binding: ItemApBinding) : RecyclerView.ViewHolder(binding.root)

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
    Holder(ItemApBinding.inflate(LayoutInflater.from(parent.context), parent, false))

  override fun onBindViewHolder(holder: Holder, position: Int) {
    val ap = getItem(position)
    val context = holder.itemView.context
    with(holder.binding) {
      ssidText.text = ap.ssid.ifEmpty { context.getString(R.string.hidden_ssid) }
      rssiText.text = context.getString(R.string.rssi, ap.lastRssi)
      detailText.text = context.getString(
        R.string.ap_detail,
        ap.mac,
        ap.channel,
        ap.band.label,
        ap.authMode,
        ap.sightings,
        ap.bestRssi,
      )
    }
  }

  private object Diff : DiffUtil.ItemCallback<ApEntry>() {
    override fun areItemsTheSame(oldItem: ApEntry, newItem: ApEntry) = oldItem.mac == newItem.mac
    override fun areContentsTheSame(oldItem: ApEntry, newItem: ApEntry) = oldItem == newItem
  }
}
