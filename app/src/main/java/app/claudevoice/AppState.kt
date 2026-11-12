package app.claudevoice

import app.claudevoice.core.UiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Gemeinsamer Zustand zwischen Service (schreibt) und Oberfläche (liest).
 * Ein StateFlow statt Service-Binding: die Activity muss den Service nicht kennen,
 * sie beobachtet nur diesen Wert.
 */
object AppState {
    val ui = MutableStateFlow(UiState())
}
