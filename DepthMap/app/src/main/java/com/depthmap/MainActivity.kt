package com.depthmap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.depthmap.ui.MainScreen
import com.depthmap.ui.theme.DepthMapTheme
import com.depthmap.viewmodel.*
import java.io.File

class MainActivity : ComponentActivity() {

    private var imagePickerCallback: ((Uri, String?) -> Unit)? = null
    private var videoPickerCallback: ((Uri, String?) -> Unit)? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(it)
            imagePickerCallback?.invoke(it, fileName)
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val fileName = getFileName(it)
            videoPickerCallback?.invoke(it, fileName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val imageProcessingViewModel: ImageProcessingViewModel = viewModel()
            val videoProcessingViewModel: VideoProcessingViewModel = viewModel()
            val isDarkPref by settingsViewModel.isDarkMode.collectAsState()
            val isDark = isDarkPref

            DepthMapTheme(darkTheme = isDark) {
                MainScreen(
                    isDarkMode = isDark,
                    homeViewModel = viewModel(),
                    modelManagerViewModel = viewModel(),
                    imageProcessingViewModel = imageProcessingViewModel,
                    videoProcessingViewModel = videoProcessingViewModel,
                    galleryViewModel = viewModel(),
                    settingsViewModel = settingsViewModel,
                    onSelectImage = {
                        imagePickerCallback = { uri, name ->
                            imageProcessingViewModel.setSelectedImage(uri, name)
                        }
                        imagePickerLauncher.launch("image/*")
                    },
                    onSelectVideo = {
                        videoPickerCallback = { uri, name ->
                            videoProcessingViewModel.setSelectedVideo(uri, name)
                        }
                        videoPickerLauncher.launch("video/*")
                    },
                    onShareFile = { path ->
                        shareFile(path)
                    },
                    onViewFile = { path ->
                        viewFile(path)
                    }
                )
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            if (nameIndex >= 0) it.getString(nameIndex) else uri.lastPathSegment
        }
    }

    private fun shareFile(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (path.endsWith(".mp4")) "video/mp4" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Depth Map"))
    }

    private fun viewFile(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = if (path.endsWith(".mp4")) "video/mp4" else "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "View"))
    }
}
