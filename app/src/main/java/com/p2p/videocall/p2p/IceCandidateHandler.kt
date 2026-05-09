package com.p2p.videocall.p2p

// Utility function to observe simple SDP events without full interface implementation
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

fun sdpObserver(onSuccess: (SessionDescription) -> Unit): SdpObserver {
    return object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            onSuccess(sdp)
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
