package com.vrtmv.app.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrtmv.app.data.recording.RecordItem
import com.vrtmv.app.data.recording.RecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecordListViewModel @Inject constructor(
    private val repository: RecordRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<RecordItem>>(emptyList())
    val items: StateFlow<List<RecordItem>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _items.value = repository.listRecords()
            } finally {
                _loading.value = false
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
            refresh()
        }
    }

    fun deleteSelected(paths: Set<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            repository.deleteMany(paths)
            refresh()
        }
    }
}
