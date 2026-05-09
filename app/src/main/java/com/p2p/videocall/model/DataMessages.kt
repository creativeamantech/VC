package com.p2p.videocall.model

import kotlinx.serialization.Serializable

@Serializable
data class AccessRequestMessage(
    val type: String = "access_request",
    val permissions: List<String>
)

@Serializable
data class AccessResponseMessage(
    val type: String = "access_response",
    val granted: List<String>,
    val denied: List<String> = emptyList()
)

@Serializable
data class AccessRevokeMessage(
    val type: String = "access_revoke",
    val permission: String
)

@Serializable
data class AccessEndMessage(
    val type: String = "access_end"
)

@Serializable
data class FileMetaMessage(
    val type: String = "file_meta",
    val name: String,
    val size: Long,
    val id: String
)

@Serializable
data class FileApprovalMessage(
    val type: String, // "file_approved" or "file_denied"
    val id: String
)

@Serializable
data class RenegoOfferMessage(
    val type: String = "renego_offer",
    val sdp: String
)

@Serializable
data class RenegoAnswerMessage(
    val type: String = "renego_answer",
    val sdp: String
)

@Serializable
data class InputMessage(
    val type: String = "input",
    val x: Float,
    val y: Float,
    val action: String,
    val ts: Long
)
