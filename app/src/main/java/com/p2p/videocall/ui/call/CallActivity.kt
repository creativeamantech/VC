package com.p2p.videocall.ui.call

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.p2p.videocall.databinding.ActivityCallBinding
import com.p2p.videocall.databinding.DialogAccessRequestBinding
import com.p2p.videocall.model.AccessRequestMessage
import com.p2p.videocall.model.AccessResponseMessage
import com.p2p.videocall.model.FileMetaMessage
import com.p2p.videocall.model.InputMessage
import com.p2p.videocall.p2p.P2PConnectionManager
import com.p2p.videocall.remote.FileTransferManager
import com.p2p.videocall.remote.RemoteControlAccessibilityService
import com.p2p.videocall.remote.ScreenShareManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.*
import java.io.File
import java.util.UUID

class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private lateinit var p2pManager: P2PConnectionManager

    private var eglBase: EglBase? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null

    private var isOfferer = false
    private var isSelfViewHidden = false

    private lateinit var screenShareManager: ScreenShareManager
    private lateinit var fileTransferManager: FileTransferManager

    private val json = Json { ignoreUnknownKeys = true }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            p2pManager.setRemoteAnswer(result.contents)
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            screenShareManager.startScreenCapture(result.data!!) { track ->
                p2pManager.peerConnection.addTrack(track, listOf("SCREEN_STREAM"))
                // Renegotiate SDP would happen here via CHANNEL_SIGNALING, simplified for scope
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            val file = File(cacheDir, "shared_file.tmp")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }

            val meta = FileMetaMessage(name = "shared_file", size = file.length(), id = UUID.randomUUID().toString())
            p2pManager.sendMessage(P2PConnectionManager.CHANNEL_META, json.encodeToString(meta))

            CoroutineScope(Dispatchers.IO).launch {
                fileTransferManager.sendFile(file) { progress ->
                    Log.d("CallActivity", "File progress: \$progress%")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isOfferer = intent.getBooleanExtra("isOfferer", false)
        val remoteSdp = intent.getStringExtra("remoteSdp")

        initWebRTC()
        setupUI()

        if (isOfferer) {
            p2pManager.createOffer()
        } else if (remoteSdp != null) {
            p2pManager.createAnswer(remoteSdp)
        }
    }

    private fun initWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        val factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        p2pManager = P2PConnectionManager(this, eglBase!!, factory)
        screenShareManager = ScreenShareManager(this, factory, eglBase!!.eglBaseContext)
        fileTransferManager = FileTransferManager(p2pManager)

        binding.localVideoView.init(eglBase?.eglBaseContext, null)
        binding.remoteVideoView.init(eglBase?.eglBaseContext, null)

        binding.localVideoView.setZOrderMediaOverlay(true)
        binding.localVideoView.setEnableHardwareScaler(true)
        binding.remoteVideoView.setEnableHardwareScaler(true)

        createLocalStream(factory)

        p2pManager.onIceGatheringCompleteCallback = { sdp ->
            runOnUiThread { showQrCode(sdp) }
        }

        p2pManager.onTrackAdded = { track ->
            runOnUiThread { track.addSink(binding.remoteVideoView) }
        }

        p2pManager.onDataChannelMessage = { channel, message ->
            runOnUiThread { handleDataMessage(channel, message) }
        }

        p2pManager.onIceConnectionStateChange = { state ->
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                runOnUiThread { Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun createLocalStream(factory: PeerConnectionFactory) {
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", audioSource)

        val videoSource = factory.createVideoSource(false)
        videoCapturer = createVideoCapturer()
        videoCapturer?.initialize(SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext), this, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = factory.createVideoTrack("ARDAMSv0", videoSource)
        localVideoTrack?.addSink(binding.localVideoView)

        p2pManager.addLocalStreamTracks(localVideoTrack!!, localAudioTrack!!)
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) return enumerator.createCapturer(deviceName, null)
        }
        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) return enumerator.createCapturer(deviceName, null)
        }
        return null
    }

    private fun setupUI() {
        binding.btnHideSelf.setOnClickListener {
            isSelfViewHidden = !isSelfViewHidden
            binding.localVideoView.visibility = if (isSelfViewHidden) View.GONE else View.VISIBLE
            if (!isSelfViewHidden) {
                binding.localVideoView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }

        binding.btnEndCall.setOnClickListener { finish() }
        binding.btnRemoteAccess.setOnClickListener { requestRemoteAccess() }

        binding.remoteVideoView.setOnTouchListener { _, event ->
            if (RemoteControlAccessibilityService.isServiceActive.value) {
                val msg = InputMessage(
                    x = event.x / binding.remoteVideoView.width,
                    y = event.y / binding.remoteVideoView.height,
                    action = "TAP",
                    ts = System.currentTimeMillis()
                )
                p2pManager.sendMessage(P2PConnectionManager.CHANNEL_INPUT, json.encodeToString(msg))
            }
            true
        }
    }

    private fun showQrCode(sdpData: String) {
        try {
            val bitmap = BarcodeEncoder().encodeBitmap(sdpData, BarcodeFormat.QR_CODE, 600, 600)
            val imageView = android.widget.ImageView(this).apply {
                setImageBitmap(bitmap)
                setPadding(32, 32, 32, 32)
            }
            AlertDialog.Builder(this)
                .setView(imageView)
                .setTitle(if (isOfferer) "Scan this to answer" else "Scan this to connect")
                .setPositiveButton("I scanned the peer's answer") { _, _ -> if (isOfferer) scanAnswerQr() }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.e("CallActivity", "Error generating QR", e)
        }
    }

    private fun scanAnswerQr() {
        scanQrLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan Peer Answer QR Code")
            setOrientationLocked(true)
        })
    }

    private fun handleDataMessage(channel: String, message: String) {
        try {
            val element = json.parseToJsonElement(message)
            val type = element.jsonObject["type"]?.jsonPrimitive?.content ?: return

            when (channel) {
                P2PConnectionManager.CHANNEL_SIGNALING -> {
                    when (type) {
                        "access_request" -> {
                            val req = json.decodeFromString<AccessRequestMessage>(message)
                            showAccessRequestDialog(req.permissions)
                        }
                        "access_response" -> {
                            val res = json.decodeFromString<AccessResponseMessage>(message)
                            if (res.granted.contains("SCREEN_CONTROL")) {
                                Toast.makeText(this, "Screen control granted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                P2PConnectionManager.CHANNEL_INPUT -> {
                    if (type == "input") {
                        val input = json.decodeFromString<InputMessage>(message)
                        val service = RemoteControlAccessibilityService.instance
                        if (service != null) {
                            val metrics = resources.displayMetrics
                            service.dispatchTap(input.x * metrics.widthPixels, input.y * metrics.heightPixels)
                        }
                    }
                }
                P2PConnectionManager.CHANNEL_META -> {
                    if (type == "file_meta") {
                        val meta = json.decodeFromString<FileMetaMessage>(message)
                        Toast.makeText(this, "Receiving file: \${meta.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CallActivity", "Error parsing message", e)
        }
    }

    private fun requestRemoteAccess() {
        val request = AccessRequestMessage(permissions = listOf("SCREEN_VIEW", "SCREEN_CONTROL", "FILE_BROWSE"))
        p2pManager.sendMessage(P2PConnectionManager.CHANNEL_SIGNALING, json.encodeToString(request))
        Toast.makeText(this, "Access requested", Toast.LENGTH_SHORT).show()
    }

    private fun showAccessRequestDialog(permissions: List<String>) {
        val dialogBinding = DialogAccessRequestBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.btnDeny.setOnClickListener {
            val response = AccessResponseMessage(granted = emptyList(), denied = permissions)
            p2pManager.sendMessage(P2PConnectionManager.CHANNEL_SIGNALING, json.encodeToString(response))
            dialog.dismiss()
        }

        dialogBinding.btnAllow.setOnClickListener {
            val granted = mutableListOf<String>()
            if (dialogBinding.cbScreenView.isChecked) granted.add("SCREEN_VIEW")
            if (dialogBinding.cbScreenControl.isChecked) granted.add("SCREEN_CONTROL")
            if (dialogBinding.cbFileBrowse.isChecked) granted.add("FILE_BROWSE")

            val response = AccessResponseMessage(granted = granted, denied = permissions - granted)
            p2pManager.sendMessage(P2PConnectionManager.CHANNEL_SIGNALING, json.encodeToString(response))
            dialog.dismiss()

            if (granted.contains("SCREEN_VIEW")) {
                startScreenCapture()
            }
            if (granted.contains("SCREEN_CONTROL") && RemoteControlAccessibilityService.instance == null) {
                Toast.makeText(this, "Please enable Accessibility Service in Settings", Toast.LENGTH_LONG).show()
            }
            if (granted.contains("FILE_BROWSE")) {
                pickFileAndSend()
            }
        }

        dialog.show()
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun pickFileAndSend() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        screenShareManager.stopScreenCapture()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        p2pManager.close()
        binding.localVideoView.release()
        binding.remoteVideoView.release()
        eglBase?.release()
    }
}
