package com.shiko.pokedex

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shiko.pokedex.camera.CardImageAnalyzer
import com.shiko.pokedex.ui.CardViewModel
import com.shiko.pokedex.ui.ScannerScreen
import com.shiko.pokedex.ui.ShikosPokedexTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() }

    private lateinit var previewView: PreviewView
    private lateinit var cardViewModel: CardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        OpenCVLoader.initDebug() // for production, prefer the async OpenCVLoader.initAsync

        previewView = PreviewView(this)

        setContent {
            cardViewModel = viewModel()
            ShikosPokedexTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScannerScreen(previewView = previewView, viewModel = cardViewModel)
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = CardImageAnalyzer(
                onCardTracked = { rect, bufferWidth, bufferHeight, rotation ->
                    cardViewModel.onCardTracked(rect, bufferWidth, bufferHeight, rotation)
                },
                onCardLost = {
                    cardViewModel.onCardLost()
                },
                onStableCard = { croppedBitmap ->
                    cardViewModel.onCardFrameStable(croppedBitmap)
                }
            )

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(ContextCompat.getMainExecutor(this), analyzer) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }
}
