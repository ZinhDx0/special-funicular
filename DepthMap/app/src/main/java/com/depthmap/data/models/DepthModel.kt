package com.depthmap.data.models

data class DepthModel(
    val id: String,
    val name: String,
    val displayName: String,
    val sizeMb: Long,
    val quality: String,
    val description: String,
    val downloadUrl: String,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadStatus: DownloadStatus = DownloadStatus.NOT_DOWNLOADED
)

enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ERROR,
    VERIFYING
}

val availableModels = listOf(
    DepthModel(
        id = "vits",
        name = "depth_anything_v2_vits",
        displayName = "ViT-Small",
        sizeMb = 95,
        quality = "Fastest, lower quality",
        description = "Lightweight model for quick depth estimation on mobile devices",
        downloadUrl = "https://huggingface.co/niye4/depthmap-anything-v2-onnx/resolve/main/depth_anything_v2_vits.onnx"
    ),
    DepthModel(
        id = "vitb",
        name = "depth_anything_v2_vitb",
        displayName = "ViT-Base",
        sizeMb = 370,
        quality = "Balanced",
        description = "Balanced model with good quality and reasonable speed",
        downloadUrl = "https://huggingface.co/niye4/depthmap-anything-v2-onnx/resolve/main/depth_anything_v2_vitb.onnx"
    ),
    DepthModel(
        id = "vitl",
        name = "depth_anything_v2_vitl",
        displayName = "ViT-Large",
        sizeMb = 1250,
        quality = "Best quality, slower",
        description = "High quality model for maximum depth accuracy",
        downloadUrl = "https://huggingface.co/niye4/depthmap-anything-v2-onnx/resolve/main/depth_anything_v2_vitl.onnx"
    )
)
