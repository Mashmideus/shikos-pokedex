package com.shiko.pokedex.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.shiko.pokedex.camera.OverlayGeometry
import com.shiko.pokedex.repository.PriceValue
import com.shiko.pokedex.repository.ScannedCard
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    previewView: PreviewView,
    viewModel: CardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val tracking by viewModel.trackingInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shiko's Pokedex") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Tracking box: follows the detected card as it moves in frame.
            Canvas(modifier = Modifier.fillMaxSize()) {
                tracking?.let { info ->
                    val mapped = OverlayGeometry.mapToView(
                        rect = info.rect,
                        bufferWidth = info.bufferWidth,
                        bufferHeight = info.bufferHeight,
                        rotationDegrees = info.rotationDegrees,
                        viewWidth = size.width,
                        viewHeight = size.height
                    )
                    drawRoundRect(
                        color = PokedexYellow,
                        topLeft = Offset(mapped.left, mapped.top),
                        size = Size(mapped.width(), mapped.height()),
                        cornerRadius = CornerRadius(24f, 24f),
                        style = Stroke(width = 5.dp.toPx())
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                when (val s = state) {
                    is CardUiState.Idle -> HintCard("Point the camera at a card")
                    is CardUiState.Scanning -> LoadingCard()
                    is CardUiState.Found -> ResultCard(s.card)
                    is CardUiState.Failed -> HintCard("Error: ${s.message}")
                }
            }
        }
    }
}

@Composable
private fun HintCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(16.dp)
    ) {
        Text(text, color = Color.White)
    }
}

@Composable
private fun LoadingCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
        Text("Reading card — hold steady…", color = Color.White)
    }
}

@Composable
private fun ResultCard(card: ScannedCard) {
    val currency = NumberFormat.getCurrencyInstance(Locale.US)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.75f), MaterialTheme.shapes.medium)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        card.imageUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = card.name,
                modifier = Modifier
                    .width(70.dp)
                    .height(98.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(card.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (card.setName.isNotBlank()) {
                Text(
                    "${card.setName} ${card.cardNumber}".trim(),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            PriceRow("Raw", card.rawPrice, currency)
        }
    }
}

@Composable
private fun PriceRow(label: String, price: PriceValue, currency: NumberFormat) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        val text = when (price) {
            is PriceValue.Available -> currency.format(price.amount)
            is PriceValue.Unavailable -> "— (${price.reason})"
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
