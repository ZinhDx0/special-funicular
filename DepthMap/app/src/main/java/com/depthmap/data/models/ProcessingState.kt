package com.depthmap.data.models

sealed class ProcessingState {
    data object Idle : ProcessingState()
    data object NoFileSelected : ProcessingState()
    data object FileSelected : ProcessingState()
    data class Processing(val progress: Float = 0f, val message: String = "") : ProcessingState()
    data class Completed(val outputPath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
    data object Cancelled : ProcessingState()
}
