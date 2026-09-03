package com.shiko.pokedex.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiko.pokedex.repository.CardRepository
import com.shiko.pokedex.repository.ScanResult
import com.shiko.pokedex.repository.ScannedCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class CardUiState {
    object Idle : CardUiState()
    object Scanning : CardUiState()
    data class Found(val card: ScannedCard) : CardUiState()
    data class Failed(val message: String) : CardUiState()
}

data class TrackingInfo(
    val rect: RectF,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotationDegrees: Int
)

class CardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CardRepository(application)

    private val _uiState = MutableStateFlow<CardUiState>(CardUiState.Idle)
    val uiState: StateFlow<CardUiState> = _uiState

    private val _trackingInfo = MutableStateFlow<TrackingInfo?>(null)
    val trackingInfo: StateFlow<TrackingInfo?> = _trackingInfo

    /**
     * Once a confident identification lands, the result is LOCKED: no further scan
     * can replace it, no matter what OCR reads on subsequent frames. It only clears
     * when the card physically leaves the frame (onCardLost). This is what stops the
     * display flickering between different cards while pointing at one card.
     */
    private var locked = false

    /** Called on every frame that has a confident card-shaped region — just positioning, no identification. */
    fun onCardTracked(rect: RectF, bufferWidth: Int, bufferHeight: Int, rotationDegrees: Int) {
        _trackingInfo.value = TrackingInfo(rect, bufferWidth, bufferHeight, rotationDegrees)
    }

    /** Called once no confident card region has been seen for a while — releases the lock and clears. */
    fun onCardLost() {
        locked = false
        _trackingInfo.value = null
        _uiState.value = CardUiState.Idle
    }

    /**
     * A new stable crop is ready to identify. Only a CONFIRMED match (one backed by
     * a real collector-number/HP match, not a name-only guess) is ever displayed —
     * an uncertain guess keeps the UI in "scanning" rather than showing a card that
     * is probably wrong. Once a confirmed match lands, it locks until the card leaves.
     */
    fun onCardFrameStable(bitmap: Bitmap) {
        if (locked) return

        if (_uiState.value !is CardUiState.Found) {
            _uiState.value = CardUiState.Scanning
        }
        viewModelScope.launch {
            repository.identifyAndPrice(bitmap).collect { result ->
                if (locked) return@collect
                when (result) {
                    is ScanResult.Success -> {
                        // Keep scanning until we're actually sure — don't show a probable-wrong guess.
                        if (!result.card.isApproximateMatch) {
                            _uiState.value = CardUiState.Found(result.card)
                            locked = true
                        }
                    }
                    is ScanResult.NoCardRecognized -> Unit // keep waiting; don't flash an error
                    is ScanResult.Error -> {
                        if (_uiState.value !is CardUiState.Found) {
                            _uiState.value = CardUiState.Failed(result.message)
                        }
                    }
                }
            }
        }
    }
}
