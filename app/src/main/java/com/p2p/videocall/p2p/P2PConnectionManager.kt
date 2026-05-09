package com.p2p.videocall.p2p

import android.content.Context
import org.webrtc.*
import java.nio.ByteBuffer

class P2PConnectionManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    lateinit var peerConnection: PeerConnection
    val dataChannels = mutableMapOf<String, DataChannel>()

    var onIceGatheringCompleteCallback: ((String) -> Unit)? = null
    var onDataChannelMessage: ((String, String) -> Unit)? = null // channelLabel, message
    var onTrackAdded: ((VideoTrack) -> Unit)? = null
    var onIceConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null

    companion object {
        const val CHANNEL_SIGNALING = "signaling"
        const val CHANNEL_INPUT     = "input"
        const val CHANNEL_FILE      = "file"
        const val CHANNEL_META      = "meta"
    }

    init {
        createPeerConnection()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onIceConnectionStateChange?.invoke(state)
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    val fullSdp = peerConnection.localDescription?.description ?: return
                    onIceGatheringCompleteCallback?.invoke(SdpExchangeHelper.encodeSdpForSharing(fullSdp))
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                dataChannels[channel.label()] = channel
                setupDataChannelObserver(channel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    onTrackAdded?.invoke(track)
                }
            }
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)!!
    }

    fun createOffer() {
        dataChannels[CHANNEL_SIGNALING] = peerConnection.createDataChannel(
            CHANNEL_SIGNALING, DataChannel.Init().apply { ordered = true }
        )
        dataChannels[CHANNEL_INPUT] = peerConnection.createDataChannel(
            CHANNEL_INPUT, DataChannel.Init().apply { ordered = false; maxRetransmits = 0 }
        )
        dataChannels[CHANNEL_FILE] = peerConnection.createDataChannel(
            CHANNEL_FILE, DataChannel.Init().apply { ordered = true }
        )
        dataChannels[CHANNEL_META] = peerConnection.createDataChannel(
            CHANNEL_META, DataChannel.Init().apply { ordered = true }
        )

        dataChannels.values.forEach { setupDataChannelObserver(it) }

        peerConnection.createOffer(sdpObserver { sdp ->
            peerConnection.setLocalDescription(sdpObserver {}, sdp)
        }, MediaConstraints())
    }

    fun createAnswer(remoteSdpStr: String) {
        val sdp = SdpExchangeHelper.decodeSdpFromSharing(remoteSdpStr)
        peerConnection.setRemoteDescription(sdpObserver {}, SessionDescription(SessionDescription.Type.OFFER, sdp))
        peerConnection.createAnswer(sdpObserver { answerSdp ->
            peerConnection.setLocalDescription(sdpObserver {}, answerSdp)
        }, MediaConstraints())
    }

    fun setRemoteAnswer(remoteSdpStr: String) {
        val sdp = SdpExchangeHelper.decodeSdpFromSharing(remoteSdpStr)
        peerConnection.setRemoteDescription(sdpObserver {}, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun setupDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val message = String(data)
                onDataChannelMessage?.invoke(channel.label(), message)
            }
        })
    }

    fun sendMessage(channelLabel: String, message: String) {
        dataChannels[channelLabel]?.send(
            DataChannel.Buffer(ByteBuffer.wrap(message.toByteArray()), false)
        )
    }

    fun addLocalStreamTracks(videoTrack: VideoTrack, audioTrack: AudioTrack) {
        peerConnection.addTrack(videoTrack, listOf("LOCAL_STREAM"))
        peerConnection.addTrack(audioTrack, listOf("LOCAL_STREAM"))
    }

    fun close() {
        dataChannels.values.forEach { it.close() }
        dataChannels.clear()
        peerConnection.close()
    }
}
