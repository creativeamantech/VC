package com.p2p.videocall.model

data class CallUiState(
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val layoutMode: LayoutMode = LayoutMode.DEFAULT,
    val isSelfViewHidden: Boolean = false,
    val isMicMuted: Boolean = false,
    val isCameraOff: Boolean = false,
    val remoteAccess: RemoteAccessState = RemoteAccessState()
)

enum class ConnectionState {
    IDLE,           // No connection yet
    AWAITING_SCAN,  // Own QR displayed, waiting for peer to scan
    SCANNING,       // User scanning peer's QR
    CONNECTING,     // ICE negotiation in progress
    CONNECTED,      // Active call
    DISCONNECTED    // Call ended or connection dropped
}

enum class LayoutMode {
    DEFAULT,        // Fullscreen remote + draggable PiP local
    SPLIT_VERTICAL  // 50/50 side-by-side vertical panels
}
