package com.p2p.videocall.model

data class RemoteAccessState(
    val sessionActive: Boolean = false,
    val grantedPermissions: Set<AccessPermission> = emptySet(),
    val pendingRequest: AccessRequest? = null,
    val fileTransfer: FileTransferProgress? = null
)

enum class AccessPermission {
    SCREEN_VIEW, SCREEN_CONTROL, FILE_BROWSE, CAMERA_GALLERY
}

data class AccessRequest(
    val permissions: List<AccessPermission>
)

data class FileTransferProgress(
    val fileName: String,
    val progress: Int, // 0 to 100
    val isSending: Boolean
)
