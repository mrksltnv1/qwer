package com.qwer.app.update

/** UI-facing state of the update flow. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val file: java.io.File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}
