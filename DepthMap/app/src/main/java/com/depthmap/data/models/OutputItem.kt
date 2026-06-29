package com.depthmap.data.models

data class OutputItem(
    val name: String,
    val path: String,
    val type: OutputType,
    val timestamp: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0
)

enum class OutputType {
    IMAGE_DEPTH,
    VIDEO_DEPTH
}
