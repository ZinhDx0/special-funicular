package com.depthmap.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.depthmap.data.models.OutputItem
import com.depthmap.data.repository.OutputRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val outputRepository = OutputRepository(application)

    private val _outputs = MutableStateFlow<List<OutputItem>>(emptyList())
    val outputs: StateFlow<List<OutputItem>> = _outputs.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _outputs.value = outputRepository.getOutputs()
        }
    }

    fun deleteOutput(output: OutputItem) {
        viewModelScope.launch {
            outputRepository.deleteOutput(output)
            refresh()
        }
    }
}
