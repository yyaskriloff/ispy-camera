package com.ispy.camera.signaling

import android.content.Context
import android.util.Log
import com.ispy.camera.StreamConfig
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded HTTP + WebSocket signaling on [StreamConfig.SIGNALING_PORT].
 * Serves the LAN test viewer at `/` and exchanges SDP/ICE over `/ws`.
 */
class SignalingServer(
    private val context: Context,
    private val expectedPin: String,
    private val listener: Listener,
    hostname: String = "0.0.0.0",
    port: Int = StreamConfig.SIGNALING_PORT,
) : NanoWSD(hostname, port) {

    interface Listener {
        fun onViewerAuthenticated(session: ViewerSession)
        fun onViewerAnswer(session: ViewerSession, sdp: String)
        fun onViewerIce(session: ViewerSession, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
        fun onViewerDisconnected(session: ViewerSession)
    }

    class ViewerSession(
        private val sendText: (String) -> Unit,
        private val closeSocket: () -> Unit,
        val id: String,
    ) {
        fun sendJson(json: JSONObject) {
            sendText(json.toString())
        }

        fun sendOffer(sdp: String) {
            sendJson(
                JSONObject()
                    .put("type", SignalingMessages.TYPE_OFFER)
                    .put("sdp", sdp),
            )
        }

        fun sendIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
            val msg = JSONObject()
                .put("type", SignalingMessages.TYPE_ICE)
                .put("candidate", candidate)
            if (sdpMid != null) msg.put("sdpMid", sdpMid)
            if (sdpMLineIndex != null) msg.put("sdpMLineIndex", sdpMLineIndex)
            sendJson(msg)
        }

        fun sendError(code: String, message: String) {
            sendJson(
                JSONObject()
                    .put("type", SignalingMessages.TYPE_ERROR)
                    .put("code", code)
                    .put("message", message),
            )
        }

        fun sendBye() {
            sendJson(JSONObject().put("type", SignalingMessages.TYPE_BYE))
        }

        fun closeQuietly() {
            closeSocket()
        }
    }

    private val activeViewer = AtomicReference<ViewerSession?>(null)

    fun currentViewer(): ViewerSession? = activeViewer.get()

    fun clearViewer(session: ViewerSession) {
        activeViewer.compareAndSet(session, null)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return ViewerSocket(handshake)
    }

    override fun serve(session: IHTTPSession): Response {
        if (isWebsocketRequested(session)) {
            return super.serve(session)
        }
        return when (session.uri.substringBefore('?')) {
            "/", "/index.html" -> serveViewerHtml()
            "/health" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"ok":true,"service":"ispy-camera"}""",
            )
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "Not found",
            )
        }
    }

    private fun serveViewerHtml(): Response {
        return try {
            val html = context.assets.open("viewer.html").bufferedReader().use { it.readText() }
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load viewer.html", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "viewer missing",
            )
        }
    }

    private inner class ViewerSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var session: ViewerSession? = null
        private var authenticated = false

        fun sendSafe(text: String) {
            try {
                send(text)
            } catch (e: IOException) {
                Log.w(TAG, "WS send failed", e)
            }
        }

        override fun onOpen() {
            Log.i(TAG, "WS open from ${handshakeRequest.remoteIpAddress}")
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean,
        ) {
            Log.i(TAG, "WS close code=$code reason=$reason remote=$initiatedByRemote")
            val s = session
            if (s != null) {
                activeViewer.compareAndSet(s, null)
                listener.onViewerDisconnected(s)
            }
            session = null
            authenticated = false
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            try {
                handleText(text)
            } catch (e: Exception) {
                Log.e(TAG, "Bad signaling message", e)
                sendSafe(
                    JSONObject()
                        .put("type", SignalingMessages.TYPE_ERROR)
                        .put("code", SignalingMessages.ERR_INVALID)
                        .put("message", e.message ?: "invalid")
                        .toString(),
                )
            }
        }

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            Log.w(TAG, "WS exception", exception)
        }

        private fun handleText(text: String) {
            val json = JSONObject(text)
            when (json.optString("type")) {
                SignalingMessages.TYPE_HELLO -> handleHello(json)
                SignalingMessages.TYPE_ANSWER -> {
                    val s = session
                    if (!authenticated || s == null) return
                    val sdp = json.optString("sdp")
                    if (sdp.isNotBlank()) listener.onViewerAnswer(s, sdp)
                }
                SignalingMessages.TYPE_ICE -> {
                    val s = session
                    if (!authenticated || s == null) return
                    val candidate = json.optString("candidate")
                    if (candidate.isBlank()) return
                    val mid = if (json.has("sdpMid")) json.optString("sdpMid") else null
                    val index = if (json.has("sdpMLineIndex")) json.optInt("sdpMLineIndex") else null
                    listener.onViewerIce(s, candidate, mid, index)
                }
                else -> {
                    sendSafe(
                        JSONObject()
                            .put("type", SignalingMessages.TYPE_ERROR)
                            .put("code", SignalingMessages.ERR_INVALID)
                            .put("message", "unknown type")
                            .toString(),
                    )
                }
            }
        }

        private fun handleHello(json: JSONObject) {
            val role = json.optString("role")
            val pin = json.optString("pin")
            if (role != SignalingMessages.ROLE_VIEWER) {
                sendSafe(
                    JSONObject()
                        .put("type", SignalingMessages.TYPE_ERROR)
                        .put("code", SignalingMessages.ERR_INVALID)
                        .put("message", "role must be viewer")
                        .toString(),
                )
                close(WebSocketFrame.CloseCode.PolicyViolation, "bad role", false)
                return
            }
            if (pin != expectedPin) {
                sendSafe(
                    JSONObject()
                        .put("type", SignalingMessages.TYPE_ERROR)
                        .put("code", SignalingMessages.ERR_BAD_PIN)
                        .put("message", "PIN rejected")
                        .toString(),
                )
                close(WebSocketFrame.CloseCode.PolicyViolation, "bad pin", false)
                return
            }

            val existing = activeViewer.get()
            if (existing != null) {
                sendSafe(
                    JSONObject()
                        .put("type", SignalingMessages.TYPE_ERROR)
                        .put("code", SignalingMessages.ERR_BUSY)
                        .put("message", "viewer already connected")
                        .toString(),
                )
                close(WebSocketFrame.CloseCode.PolicyViolation, "busy", false)
                return
            }

            val s = ViewerSession(
                sendText = { text -> sendSafe(text) },
                closeSocket = {
                    try {
                        close(WebSocketFrame.CloseCode.NormalClosure, "done", false)
                    } catch (_: Exception) {
                    }
                },
                id = Integer.toHexString(System.identityHashCode(this)),
            )
            if (!activeViewer.compareAndSet(null, s)) {
                sendSafe(
                    JSONObject()
                        .put("type", SignalingMessages.TYPE_ERROR)
                        .put("code", SignalingMessages.ERR_BUSY)
                        .put("message", "viewer already connected")
                        .toString(),
                )
                close(WebSocketFrame.CloseCode.PolicyViolation, "busy", false)
                return
            }

            session = s
            authenticated = true
            sendSafe(
                JSONObject()
                    .put("type", SignalingMessages.TYPE_WELCOME)
                    .put("role", SignalingMessages.ROLE_CAMERA)
                    .toString(),
            )
            Log.i(TAG, "Viewer authenticated id=${s.id}")
            listener.onViewerAuthenticated(s)
        }
    }

    companion object {
        private const val TAG = "SignalingServer"
    }
}
