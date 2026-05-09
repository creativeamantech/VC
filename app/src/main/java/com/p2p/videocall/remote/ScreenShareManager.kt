package com.p2p.videocall.remote

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.webrtc.*

class ScreenShareManager(
    private val context: Context,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val eglBaseContext: EglBase.Context
) {
    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    var screenTrack: VideoTrack? = null
        private set
    private val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenShareThread", eglBaseContext)

    fun startScreenCapture(mediaProjectionIntent: Intent, onTrackReady: (VideoTrack) -> Unit) {
        screenCapturer = ScreenCapturerAndroid(mediaProjectionIntent, object : MediaProjection.Callback() {
            override fun onStop() {
                stopScreenCapture()
            }
        })

        screenSource = peerConnectionFactory.createVideoSource(screenCapturer!!.isScreencast)
        screenCapturer?.initialize(surfaceTextureHelper, context, screenSource?.capturerObserver)
        screenCapturer?.startCapture(1280, 720, 15)

        screenTrack = peerConnectionFactory.createVideoTrack("SCREEN_TRACK", screenSource)
        screenTrack?.let { onTrackReady(it) }
    }

    fun stopScreenCapture() {
        try {
            screenCapturer?.stopCapture()
        } catch (e: Exception) {}
        screenCapturer?.dispose()
        screenCapturer = null
        screenSource?.dispose()
        screenSource = null
        screenTrack?.dispose()
        screenTrack = null
    }
}
