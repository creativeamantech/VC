package com.p2p.videocall.ui.connect

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.p2p.videocall.databinding.ActivityConnectBinding
import com.p2p.videocall.p2p.NsdDiscoveryManager
import com.p2p.videocall.ui.call.CallActivity
import kotlinx.coroutines.launch

class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var nsdManager: NsdDiscoveryManager

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            startCallActivity(result.contents, isOfferer = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nsdManager = NsdDiscoveryManager(this)

        binding.btnStartCall.setOnClickListener {
            startCallActivity(null, isOfferer = true)
        }

        binding.btnJoinCall.setOnClickListener {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Peer QR Code")
                setCameraId(0)
                setBeepEnabled(false)
                setBarcodeImageEnabled(false)
            }
            scanQrLauncher.launch(options)
        }

        handleDeepLink(intent)
        startNsdDiscovery()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleDeepLink(it) }
    }

    private fun handleDeepLink(intent: Intent) {
        val action = intent.action
        val data = intent.data
        if (Intent.ACTION_VIEW == action && data != null && data.scheme == "myapp" && data.host == "call") {
            val sdp = data.getQueryParameter("sdp")
            if (sdp != null) {
                startCallActivity(sdp, isOfferer = false)
            }
        }
    }

    private fun startNsdDiscovery() {
        nsdManager.startDiscovery()
        lifecycleScope.launch {
            nsdManager.discoveredPeerIp.collect { ip ->
                if (ip != null) {
                    binding.tvDiscoveryStatus.text = "Peer found on LAN: $ip"
                    binding.btnStartCall.text = "Connect to $ip"
                    binding.btnStartCall.setOnClickListener {
                        Toast.makeText(this@ConnectActivity, "Connecting via LAN (TCP setup would follow)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startCallActivity(remoteSdp: String?, isOfferer: Boolean) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("isOfferer", isOfferer)
            remoteSdp?.let { putExtra("remoteSdp", it) }
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdManager.stop()
    }
}
