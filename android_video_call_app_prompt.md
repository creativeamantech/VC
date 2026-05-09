# 📱 Android Video Calling App — AI Development Prompt (Pure P2P, No Server)

> Use this prompt with any AI coding assistant (Claude, Gemini, Cursor, Copilot) to generate a
> production-ready Android video calling app with **zero backend server dependency**.

---

## 🎯 Project Overview

Build a production-ready **Android video calling application** in **Kotlin** using the
**Google WebRTC library** in a fully **peer-to-peer (P2P) mode** — no signaling server,
no backend, no cloud dependency of any kind.

All communication (call setup, video, audio, data, remote access events, file transfer)
happens **directly between the two devices** over an encrypted WebRTC connection.

### Feature Summary

- Full-screen remote video feed with draggable local PiP overlay
- **Hide Self View** — hides local preview while keeping outgoing stream active
- **Vertical Split-Screen** — two full-height equal panels side by side
- **Remote Phone Access** — consent-gated screen view, touch control, file browse, gallery
- **Zero server** — P2P only, connection bootstrapped via QR code or share link

---

## 🔗 P2P Connection Strategy (No Server)

### How Two Devices Connect Without a Server

WebRTC requires an initial **SDP offer/answer exchange** to establish a peer connection.
Without a server, bootstrap this exchange using one of these **out-of-band** methods:

#### Method A — QR Code Exchange (Primary, Recommended)

```
Device A (Caller)                          Device B (Receiver)
──────────────────                         ─────────────────────
1. Generate SDP Offer
2. Compress + encode as Base64
3. Display as QR code on screen
                                           4. Scan QR with camera
                                           5. Decode SDP Offer
                                           6. Generate SDP Answer
                                           7. Display Answer as QR
8. Scan Answer QR
9. Decode + set remote description
10. ICE candidates exchanged               10. ICE candidates exchanged
         ↓                                          ↓
         └──────── Direct P2P Connection ──────────┘
```

#### Method B — Share Link / NFC / Clipboard

- Encode the SDP offer as a compressed Base64 URL: `myapp://call?sdp=<base64>`
- Share via any channel the user chooses: SMS, WhatsApp, NFC tap, clipboard copy
- Receiver opens the link → app auto-decodes offer → generates answer → shares back
- Same compression + Base64 encoding for the answer

#### Method C — Local Network Auto-Discovery (Same Wi-Fi)

- Use **Android NSD (Network Service Discovery)** / mDNS to find peers on the same Wi-Fi
- Broadcast service type `_videocall._tcp`
- When a peer is discovered, open a direct **TCP socket** to exchange SDP
- No internet required — works fully offline on local network

```kotlin
// NSD registration
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "VideoCall_${Build.MODEL}"
    serviceType  = "_videocall._tcp"
    port         = 47832
}
nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

// NSD discovery
nsdManager.discoverServices("_videocall._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
```

---

### ICE / STUN Configuration (No TURN Server)

For P2P across different networks use **public STUN servers only** (free, no account needed).
STUN only assists with NAT traversal — no media data passes through it.

```kotlin
val iceServers = listOf(
    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer()
)

val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
    sdpSemantics          = PeerConnection.SdpSemantics.UNIFIED_PLAN
    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    bundlePolicy          = PeerConnection.BundlePolicy.MAXBUNDLE
    rtcpMuxPolicy         = PeerConnection.RtcpMuxPolicy.REQUIRE
}
```

> ⚠️ No TURN server means the connection may fail on very restrictive NAT/firewall networks
> (some corporate networks, certain mobile carriers). Handle this gracefully with a message
> suggesting the user switch to Wi-Fi or a personal hotspot.

---

### SDP Compression for QR / Link Sharing

SDP offers are typically 1–3 KB. Compress before encoding to keep QR codes scannable:

```kotlin
fun encodeSdpForSharing(sdp: String): String {
    val compressed = ByteArrayOutputStream().also { baos ->
        GZIPOutputStream(baos).use { it.write(sdp.toByteArray()) }
    }.toByteArray()
    return Base64.encodeToString(compressed, Base64.URL_SAFE or Base64.NO_WRAP)
}

fun decodeSdpFromSharing(encoded: String): String {
    val compressed = Base64.decode(encoded, Base64.URL_SAFE)
    return GZIPInputStream(compressed.inputStream()).bufferedReader().readText()
}
```

---

### P2P Connection Manager (`P2PConnectionManager.kt`)

```kotlin
class P2PConnectionManager(
    private val context: Context,
    private val eglBase: EglBase
) {
    private lateinit var peerConnection: PeerConnection
    private val dataChannels = mutableMapOf<String, DataChannel>()

    companion object {
        const val CHANNEL_SIGNALING = "signaling"  // access requests, permission events, renegotiation
        const val CHANNEL_INPUT     = "input"      // remote touch events (unreliable, low-latency)
        const val CHANNEL_FILE      = "file"       // binary file chunks (reliable, ordered)
        const val CHANNEL_META      = "meta"       // file metadata and ack messages
    }

    fun createOffer(onOfferReady: (String) -> Unit) {
        // Offerer creates all DataChannels before generating the offer
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

        peerConnection.createOffer(sdpObserver { sdp ->
            peerConnection.setLocalDescription(sdpObserver {}, sdp)
            // Wait for ICE gathering to complete (GATHER_CONTINUALLY collects all candidates)
            // then encode the full local description
        }, MediaConstraints())
    }

    // Called when ICE gathering state == COMPLETE
    fun onIceGatheringComplete(onOfferReady: (String) -> Unit) {
        val fullSdp = peerConnection.localDescription?.description ?: return
        onOfferReady(encodeSdpForSharing(fullSdp))
    }
}
```

---

## 🧩 Core Features

### 1. Video Call Screen (Main UI)

- Full-screen remote video feed as the background layer
- A **draggable Picture-in-Picture (PiP)** overlay showing the local camera feed
- Floating control bar at the bottom with:
  - Mute microphone
  - Toggle camera on/off
  - End call
  - Flip camera (front/back)
  - **Hide Self View** button
  - **Split Screen** button
  - **Remote Access** button

---

### 2. Hide Self View

- A dedicated **"Hide My View"** toggle button (eye / eye-slash icon)
- When **activated**:
  - Local PiP becomes completely invisible (`View.GONE`) — zero footprint on screen
  - Local camera stream **continues transmitting** to the peer (only the preview is hidden)
  - Subtle ghost/camera icon indicator so the user knows the camera is still active
- When **deactivated**:
  - PiP view animates back with `fade + scale` (150ms)

```kotlin
fun toggleSelfView(hide: Boolean) {
    localVideoView.visibility = if (hide) View.GONE else View.VISIBLE
    if (!hide) {
        localVideoView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
    }
    // CRITICAL: do NOT call localVideoTrack.setEnabled(false)
    // That stops the outgoing stream — only the local View should be hidden
}
```

---

### 3. Vertical Split-Screen Mode

- When **activated**:
  - Layout switches to two **equal vertical panels** (full height, 50% width each)
  - **Left panel** → Remote video feed
  - **Right panel** → Local camera feed
  - Both use `SCALE_ASPECT_FILL` / `CENTER_CROP` — no letterboxing
  - Control bar overlays the bottom spanning full width (semi-transparent)
  - Hide Self View still works: hides right panel, expands left to full screen
- When **deactivated**:
  - Animates back to fullscreen + PiP via `TransitionManager.beginDelayedTransition()` (200ms)

---

### 4. Remote Phone Access (With Permission, P2P Only)

All remote access messages travel **exclusively over WebRTC DataChannels** established
during the initial P2P handshake. No server is ever involved.

#### 4a. Access Request Flow

```
Requester taps "Request Phone Access"
        ↓
JSON sent over CHANNEL_SIGNALING DataChannel:
  { "type": "access_request", "permissions": ["screen_view", "files"] }
        ↓
Remote device receives via DataChannel.Observer.onMessage()
        ↓
Remote shows full-screen Permission Dialog:
  ┌─────────────────────────────────┐
  │  [Peer] wants to access         │
  │  your phone.                    │
  │                                 │
  │  Select what to allow:          │
  │  ☐ View my screen (read-only)   │
  │  ☐ Control my screen (touch)    │
  │  ☐ Browse my files              │
  │  ☐ Access my camera/gallery     │
  │                                 │
  │   [Deny]        [Allow]         │
  └─────────────────────────────────┘
        ↓
Remote sends response over CHANNEL_SIGNALING:
  { "type": "access_response", "granted": ["screen_view"] }
        ↓
Session begins — persistent orange banner shown on remote device
```

---

#### 4b. Screen Sharing (View Only) — P2P

- Remote starts **MediaProjection** screen capture
- Captured frames fed into a **secondary WebRTC VideoTrack** added to the same `PeerConnection`
  via `addTrack()` (no new connection — UNIFIED_PLAN supports multiple tracks)
- Requester receives screen frames on the second `VideoTrack`, displayed in an overlay panel
- Remote shows persistent orange banner: `"[Peer] is viewing your screen"` + one-tap Stop
- Track renegotiation is done over **CHANNEL_SIGNALING** (see Section 4f)

```kotlin
fun startScreenShare(mediaProjectionIntent: Intent) {
    val screenCapturer = ScreenCapturerAndroid(mediaProjectionIntent, object : MediaProjection.Callback() {
        override fun onStop() { stopScreenShare() }
    })
    val screenSource = peerConnectionFactory.createVideoSource(true)
    screenCapturer.initialize(surfaceTextureHelper, context, screenSource.capturerObserver)
    screenCapturer.startCapture(1280, 720, 15)
    val screenTrack = peerConnectionFactory.createVideoTrack("SCREEN", screenSource)
    peerConnection.addTrack(screenTrack, listOf("SCREEN_STREAM"))
    renegotiate()  // Exchange new SDP over CHANNEL_SIGNALING — no server needed
}
```

---

#### 4c. Screen Control (Touch Injection) — P2P

- Touch events sent over **CHANNEL_INPUT** (unreliable, low-latency, no retransmits)
- Remote device injects gestures via `AccessibilityService`
- ⚠️ Remote user must manually enable `RemoteControlAccessibilityService` in Android Settings;
  the app shows a deep-link guided prompt

```kotlin
// Requester: send touch over DataChannel
fun sendTouchEvent(x: Float, y: Float, action: String) {
    val json = """{"type":"input","x":$x,"y":$y,"action":"$action","ts":${System.currentTimeMillis()}}"""
    dataChannels[CHANNEL_INPUT]?.send(
        DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray()), false)
    )
}

// Remote: AccessibilityService injects gesture
class RemoteControlAccessibilityService : AccessibilityService() {
    fun onReceiveInputEvent(x: Float, y: Float) {
        val stroke = GestureDescription.StrokeBuilder()
            .setPath(Path().apply { moveTo(x, y) })
            .setStartTime(0).setDuration(50).build()
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
```

---

#### 4d. File Transfer — P2P via DataChannel

- File metadata over **CHANNEL_META**; binary chunks over **CHANNEL_FILE** (64 KB each)
- Remote gets a per-file confirmation dialog before any transfer starts
- Real-time progress bar on both sides

```kotlin
fun sendFile(file: File) {
    // Step 1: send metadata and wait for approval
    val meta = """{"type":"file_meta","name":"${file.name}","size":${file.length()},"id":"${UUID.randomUUID()}"}"""
    dataChannels[CHANNEL_META]?.send(DataChannel.Buffer(ByteBuffer.wrap(meta.toByteArray()), false))
}

fun onFileApproved(file: File) {
    // Step 2: stream chunks after remote approves
    val buffer = ByteArray(65536)
    file.inputStream().use { stream ->
        var n: Int
        while (stream.read(buffer).also { n = it } != -1) {
            dataChannels[CHANNEL_FILE]?.send(
                DataChannel.Buffer(ByteBuffer.wrap(buffer, 0, n), true)
            )
        }
    }
}
```

---

#### 4e. DataChannel Message Protocol

All control messages travel over **CHANNEL_SIGNALING** as JSON:

```json
{ "type": "access_request",  "permissions": ["screen_view", "files"] }
{ "type": "access_response", "granted": ["screen_view"], "denied": ["files"] }
{ "type": "access_revoke",   "permission": "screen_view" }
{ "type": "access_end" }

{ "type": "file_meta",       "name": "report.pdf", "size": 204800, "id": "abc123" }
{ "type": "file_approved",   "id": "abc123" }
{ "type": "file_denied",     "id": "abc123" }

{ "type": "renego_offer",    "sdp": "<escaped SDP string>" }
{ "type": "renego_answer",   "sdp": "<escaped SDP string>" }
```

Touch input messages travel over **CHANNEL_INPUT**:

```json
{ "type": "input", "x": 320.5, "y": 640.0, "action": "TAP",   "ts": 1715200000000 }
{ "type": "input", "x": 100.0, "y": 200.0, "action": "SWIPE",  "ts": 1715200000100 }
{ "type": "input", "x": 160.0, "y": 400.0, "action": "LONG_PRESS", "ts": 1715200000200 }
```

---

#### 4f. Renegotiation for Screen Share Track (No Server)

When the screen share track is added/removed, the `PeerConnection` renegotiates.
Because there is no signaling server, the new SDP offer/answer is exchanged over
**CHANNEL_SIGNALING**:

```kotlin
fun renegotiate() {
    peerConnection.createOffer(sdpObserver { sdp ->
        peerConnection.setLocalDescription(sdpObserver {}, sdp)
        val msg = """{"type":"renego_offer","sdp":"${sdp.description.escapeForJson()}"}"""
        dataChannels[CHANNEL_SIGNALING]?.send(
            DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false)
        )
    }, MediaConstraints())
}

fun onRenegotiationOfferReceived(offerSdp: String) {
    peerConnection.setRemoteDescription(sdpObserver {}, SessionDescription(Type.OFFER, offerSdp))
    peerConnection.createAnswer(sdpObserver { sdp ->
        peerConnection.setLocalDescription(sdpObserver {}, sdp)
        val msg = """{"type":"renego_answer","sdp":"${sdp.description.escapeForJson()}"}"""
        dataChannels[CHANNEL_SIGNALING]?.send(
            DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false)
        )
    }, MediaConstraints())
}
```

---

## 🏗️ Technical Architecture

### Tech Stack

| Component | Choice |
|-----------|--------|
| Language | Kotlin |
| Min SDK | API 26 (Android 8.0) |
| Video Engine | `org.webrtc:google-webrtc:1.0.32006` |
| Initial Signaling | QR code / deep-link URL / NFC / LAN socket — **no server** |
| Renegotiation | WebRTC DataChannel (`CHANNEL_SIGNALING`) |
| Remote Events | WebRTC DataChannel (`CHANNEL_SIGNALING`) |
| Touch Injection | WebRTC DataChannel (`CHANNEL_INPUT`) |
| File Transfer | WebRTC DataChannel (`CHANNEL_FILE` + `CHANNEL_META`) |
| LAN Discovery | Android NSD (mDNS) |
| Screen Capture | MediaProjection API |
| Input Injection | AccessibilityService + GestureDescription |
| UI | ConstraintLayout + ViewBinding |
| Architecture | MVVM + StateFlow |

---

### Project Structure

```
app/
├── ui/
│   ├── connect/
│   │   ├── ConnectActivity.kt           # QR display + scanner for SDP exchange
│   │   ├── QrDisplayFragment.kt         # Shows own SDP offer as QR code
│   │   └── QrScanFragment.kt            # Scans peer QR to decode SDP
│   ├── call/
│   │   ├── CallActivity.kt              # Main video call screen
│   │   ├── CallViewModel.kt             # UI state (StateFlow)
│   │   ├── CallLayoutManager.kt         # Layout mode switching + animations
│   │   └── AccessPanelFragment.kt       # Remote access permission panel (swipe up)
│   ├── access/
│   │   ├── AccessRequestDialog.kt       # Full-screen consent dialog (remote side)
│   │   ├── FileBrowserFragment.kt       # Scoped file tree browser
│   │   └── FileTransferProgressView.kt  # Live transfer progress bar
│   └── common/
│       └── AnimationUtils.kt
├── p2p/
│   ├── P2PConnectionManager.kt          # WebRTC PeerConnection + all DataChannels
│   ├── SdpExchangeHelper.kt             # QR encode/decode, gzip+Base64, deep-link
│   ├── IceCandidateHandler.kt           # ICE gathering, completion detection
│   └── NsdDiscoveryManager.kt           # Local network peer discovery (same Wi-Fi)
├── remote/
│   ├── RemoteAccessManager.kt           # Permission state + session lifecycle
│   ├── ScreenShareManager.kt            # MediaProjection + secondary VideoTrack
│   ├── RemoteControlService.kt          # AccessibilityService for gesture injection
│   └── FileTransferManager.kt          # Chunked send/receive over DataChannel
├── model/
│   ├── CallState.kt                     # ConnectionState, LayoutMode enums
│   └── RemoteAccessState.kt            # AccessPermission, AccessRequest, FileProgress
└── res/
    └── layout/
        ├── activity_connect.xml         # QR exchange screen
        ├── activity_call.xml            # Default call layout (fullscreen + PiP)
        ├── activity_call_split.xml      # Vertical split layout
        └── dialog_access_request.xml   # Permission consent dialog
```

---

## 📐 Layout Specifications

### Default Mode (`activity_call.xml`)

```xml
<androidx.constraintlayout.widget.ConstraintLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#0D0D0D">

    <org.webrtc.SurfaceViewRenderer
        android:id="@+id/remoteVideoView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <org.webrtc.SurfaceViewRenderer
        android:id="@+id/localVideoView"
        android:layout_width="120dp"
        android:layout_height="160dp"
        android:elevation="8dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        android:layout_margin="16dp" />

    <LinearLayout
        android:id="@+id/controlBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="#CC000000"
        android:padding="16dp"
        app:layout_constraintBottom_toBottomOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

### Split Screen Mode (`activity_call_split.xml`)

```xml
<FrameLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:orientation="horizontal"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:weightSum="2">

        <org.webrtc.SurfaceViewRenderer
            android:id="@+id/remoteVideoView"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1" />

        <org.webrtc.SurfaceViewRenderer
            android:id="@+id/localVideoView"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/controlBar"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:background="#CC000000"
        android:padding="16dp" />

</FrameLayout>
```

---

## 🎛️ UI State Model

```kotlin
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

data class RemoteAccessState(
    val sessionActive: Boolean = false,
    val grantedPermissions: Set<AccessPermission> = emptySet(),
    val pendingRequest: AccessRequest? = null,
    val fileTransfer: FileTransferProgress? = null
)

enum class AccessPermission {
    SCREEN_VIEW, SCREEN_CONTROL, FILE_BROWSE, CAMERA_GALLERY
}
```

---

## 🔒 Security Rules

| Rule | Implementation |
|------|---------------|
| No silent access | Every permission requires an explicit tap on the consent dialog |
| Persistent indicator | Orange banner + foreground notification for the full session |
| Instant revoke | Each permission individually revocable mid-call via Access Panel |
| Scoped file access | File browser limited to Downloads, Documents, Pictures only |
| Session auto-end | All permissions revoked when DataChannel closes or call ends |
| End-to-end encryption | WebRTC DTLS-SRTP encrypts all tracks and DataChannels |
| No relay | No TURN server — media never passes through any third party |
| Audit log | Local log of all access grant/revoke events on device |

---

## ✅ Acceptance Criteria

| Feature | Requirement |
|--------|-------------|
| No server | Zero backend; app works without internet (LAN) or with STUN only (WAN) |
| QR connect | Devices pair by scanning each other's QR-encoded SDP |
| Link fallback | SDP shareable as compressed deep-link URL via any app |
| LAN discovery | Auto-finds peers on same Wi-Fi via NSD / mDNS |
| Hide Self View | Local PiP `View.GONE`, outgoing stream unaffected |
| Hide indicator | Pulsing dot visible while camera transmits but preview is hidden |
| Split Screen | Full-height equal panels, edge-to-edge, no letterbox |
| Split + Hide | Hides right panel, expands left to full screen |
| Smooth transitions | All layout changes animate 150–200ms |
| Access consent | Full permission dialog before any remote access begins |
| No silent access | Zero permissions without explicit remote user tap |
| Screen view | Remote screen streamed as second WebRTC VideoTrack, pure P2P |
| Screen control | Gestures injected via AccessibilityService over DataChannel only |
| File transfer | Chunked over DataChannel, per-file approval by remote required |
| Renegotiation | Screen track changes handled by re-offer/answer over DataChannel |
| Instant revoke | Each permission revocable mid-session without ending call |
| Encrypted | DTLS-SRTP on all media and DataChannels, zero relay |

---

## 🚀 Getting Started

1. Add dependency to `build.gradle`:
   ```gradle
   implementation 'org.webrtc:google-webrtc:1.0.32006'
   ```
2. Install on two physical Android devices
3. **Device A** taps "Start Call" → QR code is displayed (compressed SDP offer)
4. **Device B** taps "Join Call" → scans Device A's QR
5. Device B generates answer → displays answer QR
6. Device A scans answer QR → P2P connection established
7. *(Same Wi-Fi only)* Devices auto-discover each other via NSD — no QR scan needed
8. For Screen Control: remote user enables `RemoteControlAccessibilityService` via the
   in-app guided deep-link prompt

---

## 📋 Required Android Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<service
    android:name=".remote.RemoteControlAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

---

*Generated prompt — Android P2P video call app.*
*No server. No relay. No cloud. All communication is direct, encrypted, and user-consented.*
