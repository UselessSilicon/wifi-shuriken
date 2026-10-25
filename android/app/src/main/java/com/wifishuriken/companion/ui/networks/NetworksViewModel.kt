package com.wifishuriken.companion.ui.networks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wifishuriken.companion.data.CompanionDb
import com.wifishuriken.companion.data.NetworkEntity
import com.wifishuriken.companion.data.NetworkFilter
import com.wifishuriken.companion.data.NetworkSort
import com.wifishuriken.companion.data.NetworkStats
import com.wifishuriken.companion.ui.drives.DateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val NETWORKS_PAGE = 100

/** Networks browse state (design §6.3): filter, sort, a paged list and the header stats. */
class NetworksViewModel(app: Application) : AndroidViewModel(app) {
  private val dao = CompanionDb.get(app).dao()
  private val _filter = MutableStateFlow(NetworkFilter())
  private val _sort = MutableStateFlow(NetworkSort.MOST_SEEN)
  private val _range = MutableStateFlow(DateRange.ALL)
  private val _items = MutableStateFlow<List<NetworkEntity>>(emptyList())
  private val _stats = MutableStateFlow(NetworkStats(emptyMap()))
  val filter: StateFlow<NetworkFilter> = _filter
  val sort: StateFlow<NetworkSort> = _sort
  val range: StateFlow<DateRange> = _range
  val items: StateFlow<List<NetworkEntity>> = _items
  val stats: StateFlow<NetworkStats> = _stats

  private var started = false
  private var endReached = false
  private var job: Job? = null

  /** Scopes the list to one drive, or all drives when [tripId] is null. */
  fun setScope(tripId: Long?) {
    if (started && _filter.value.tripId == tripId) return
    started = true
    _filter.value = _filter.value.copy(tripId = tripId)
    reload()
  }

  fun update(change: (NetworkFilter) -> NetworkFilter) {
    _filter.value = change(_filter.value)
    reload(debounceMs = if (_filter.value.query != null) 250 else 0)
  }

  fun setSort(sort: NetworkSort) {
    _sort.value = sort
    reload()
  }

  fun setRange(range: DateRange) {
    _range.value = range
    update { it.copy(fromUtc = range.cutoffUtc()) }
  }

  fun loadMore() {
    if (endReached || job?.isActive == true) return
    val f = _filter.value
    val s = _sort.value
    job = viewModelScope.launch(Dispatchers.IO) {
      val next = dao.networksPage(f, s, NETWORKS_PAGE, _items.value.size)
      endReached = next.size < NETWORKS_PAGE
      _items.value += next
    }
  }

  private fun reload(debounceMs: Long = 0) {
    job?.cancel()
    endReached = false
    val f = _filter.value
    val s = _sort.value
    job = viewModelScope.launch(Dispatchers.IO) {
      if (debounceMs > 0) delay(debounceMs)
      val first = dao.networksPage(f, s, NETWORKS_PAGE, 0)
      endReached = first.size < NETWORKS_PAGE
      _items.value = first
      _stats.value = dao.networkStats(f)
    }
  }
}
