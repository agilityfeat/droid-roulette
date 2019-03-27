package ventures.webrtc.webrtcroulette.videocall

/**
* Created by Rafael on 01-18-18.
*/


import android.util.Log
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class MessageType(val value: String) {
    SDPMessage("sdp"),
    ICEMessage("ice"),
    MatchMessage("match"),
    PeerLeft("peer-left");

    override fun toString() = value
}

open class ClientMessage(val type: MessageType)

data class SDPMessage(val sdpType: Int, val sdp: String) : ClientMessage(MessageType.SDPMessage)
data class ICEMessage(val label: Int, val id: String, val candidate: String) : ClientMessage(MessageType.ICEMessage)
data class MatchMessage(val match: String, val offer: Boolean) : ClientMessage(MessageType.MatchMessage)
class PeerLeft : ClientMessage(MessageType.PeerLeft)

class SignalingWebSocket : WebSocketListener() {

    private var webSocket: WebSocket? = null
    var messageHandler: ((ClientMessage) -> Unit)? = null

    override fun onOpen(webSocket: WebSocket?, response: Response?) {
        Log.i(TAG, "WebSocket: OPEN")
        this.webSocket = webSocket
    }

    override fun onMessage(webSocket: WebSocket?, text: String?) {
        Log.i(TAG, "WebSocket: Got message $text")
        val json = JSONObject(text)
        val type = json.getString("type")
        val clientMessage =
            when(type) {
                "sdp" ->
                    SDPMessage(json.getInt("sdpType"), json.getString("sdp"))
                "ice" ->
                    ICEMessage(json.getInt("label"), json.getString("id"), json.getString("candidate"))
                "matched" ->
                    MatchMessage(json.getString("match"), json.getBoolean("offer"))
                "peer-left" ->
                    PeerLeft()
                else ->
                    null
            }
        Log.i(TAG, "WebSocket: Decoded message as $clientMessage")
        if(clientMessage != null) this.messageHandler?.invoke(clientMessage)
    }

    override fun onClosing(webSocket: WebSocket?, code: Int, reason: String?) {
        Log.i(TAG, "WebSocket: closed")
        super.onClosing(webSocket, code, reason)
        this.webSocket = null
    }

    override fun onFailure(webSocket: WebSocket?, t: Throwable?, response: Response?) {
        super.onFailure(webSocket, t, response)
        Log.e(TAG, "WebSocket: Failure($t)")
    }

    private fun send(clientMessage: ClientMessage) {
        val json = JSONObject()
        json.put("type", clientMessage.type)
        when(clientMessage) {
            is SDPMessage -> {
                json.put("sdpType", clientMessage.sdpType)
                json.put("sdp", clientMessage.sdp)
            }
            is ICEMessage -> {
                json.put("candidate", clientMessage.candidate)
                json.put("id", clientMessage.id)
                json.put("label", clientMessage.label)
            }
            else -> {
                Log.w(TAG, "Message of type '${clientMessage.type.value}' can't be sent to the server")
                return
            }
        }
        Log.i(TAG, "WebSocket: Sending $json")
        webSocket?.send(json.toString())
    }

    fun close() {
        webSocket?.close(1000, null)
    }

    fun sendSDP(type: Int, sdp: String) {
        send(SDPMessage(type, sdp))
    }

    fun sendCandidate(label: Int, id: String, candidate: String) {
        send(ICEMessage(label, id, candidate))
    }

    companion object {
        private val TAG = "SignalingWebSocket"
    }
}