package ventures.webrtc.webrtcroulette.videocall

/**
* Created by Rafael on 01-18-18.
*/

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.webrtc.*
import ventures.webrtc.webrtcroulette.R
import java.util.concurrent.Executors

enum class VideoCallStatus(val label: Int, val color: Int) {
    UNKNOWN(R.string.status_unknown, R.color.colorUnknown),
    CONNECTING(R.string.status_connecting, R.color.colorConnecting),
    MATCHING(R.string.status_matching, R.color.colorMatching),
    FAILED(R.string.status_failed, R.color.colorFailed),
    CONNECTED(R.string.status_connected, R.color.colorConnected),
    FINISHED(R.string.status_finished, R.color.colorConnected);
}

data class VideoRenderers(private val localView: SurfaceViewRenderer?, private val remoteView: SurfaceViewRenderer?) {
    val localRenderer: (VideoRenderer.I420Frame) -> Unit =
        if(localView == null) this::sink else { f -> localView.renderFrame(f) }
    val remoteRenderer: (VideoRenderer.I420Frame) -> Unit =
        if(remoteView == null) this::sink else { f -> remoteView.renderFrame(f) }

    private fun sink(frame: VideoRenderer.I420Frame) {
        Log.w("VideoRenderer", "Missing surface view, dropping frame")
        VideoRenderer.renderFrameDone(frame)
    }
}

class VideoCallSession(
        private val context: Context,
        private val onStatusChangedListener: (VideoCallStatus) -> Unit,
        private val signaler: SignalingWebSocket,
        private val videoRenderers: VideoRenderers)  {

    private var peerConnection : PeerConnection? = null
    private var factory : PeerConnectionFactory? = null
    private var isOfferingPeer = false
    private var videoSource : VideoSource? = null
    private var audioSource : AudioSource? = null
    private val eglBase = EglBase.create()
    private var videoCapturer: VideoCapturer? = null

    val renderContext: EglBase.Context
        get() = eglBase.eglBaseContext

    class SimpleRTCEventHandler (
            private val onIceCandidateCb: (IceCandidate) -> Unit,
            private val onAddStreamCb: (MediaStream) -> Unit,
            private val onRemoveStreamCb: (MediaStream) -> Unit) : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate?) {
            if(candidate != null) onIceCandidateCb(candidate)
        }

        override fun onAddStream(stream: MediaStream?) {
            if (stream != null) onAddStreamCb(stream)
        }

        override fun onRemoveStream(stream: MediaStream?) {
            if(stream != null) onRemoveStreamCb(stream)
        }

        override fun onDataChannel(chan: DataChannel?) { Log.w(TAG, "onDataChannel: $chan") }

        override fun onIceConnectionReceivingChange(p0: Boolean) { Log.w(TAG, "onIceConnectionReceivingChange: $p0") }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) { Log.w(TAG, "onIceConnectionChange: $newState") }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) { Log.w(TAG, "onIceGatheringChange: $newState") }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) { Log.w(TAG, "onSignalingChange: $newState") }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) { Log.w(TAG, "onIceCandidatesRemoved: $candidates") }

        override fun onRenegotiationNeeded() { Log.w(TAG, "onRenegotiationNeeded") }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) { }
    }

    init {
        signaler.messageHandler = this::onMessage
        this.onStatusChangedListener(VideoCallStatus.MATCHING)
        executor.execute(this::init)
    }

    private fun init() {
        PeerConnectionFactory.initializeAndroidGlobals(context, true)
        val opts = PeerConnectionFactory.Options()
        opts.networkIgnoreMask = 0

        factory = PeerConnectionFactory(opts)
        factory?.setVideoHwAccelerationOptions(eglBase.eglBaseContext, eglBase.eglBaseContext)

        val iceServers = arrayListOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        val rtcCfg = PeerConnection.RTCConfiguration(iceServers)
        rtcCfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        val rtcEvents = SimpleRTCEventHandler(this::handleLocalIceCandidate, this::addRemoteStream, this::removeRemoteStream)
        peerConnection = factory?.createPeerConnection(rtcCfg, constraints, rtcEvents)
        setupMediaDevices()
    }

    private fun start() {
        executor.execute(this::maybeCreateOffer)
    }

    private fun maybeCreateOffer() {
        if(isOfferingPeer) {
            peerConnection?.createOffer(SDPCreateCallback(this::createDescriptorCallback), MediaConstraints())
        }
    }

    private fun handleLocalIceCandidate(candidate: IceCandidate) {
        Log.w(TAG, "Local ICE candidate: $candidate")
        signaler.sendCandidate(candidate.sdpMLineIndex, candidate.sdpMid, candidate.sdp)
    }

    private fun addRemoteStream(stream: MediaStream) {
        onStatusChangedListener(VideoCallStatus.CONNECTED)
        Log.i(TAG, "Got remote stream: $stream")
        executor.execute {
            if(stream.videoTracks.isNotEmpty()) {
                val remoteVideoTrack = stream.videoTracks.first()
                remoteVideoTrack.setEnabled(true)
                remoteVideoTrack.addRenderer(VideoRenderer(videoRenderers.remoteRenderer))
            }
        }
    }

    private fun removeRemoteStream(@Suppress("UNUSED_PARAMETER") _stream: MediaStream) {
        // We lost the stream, lets finish
        Log.i(TAG, "Bye")
        onStatusChangedListener(VideoCallStatus.FINISHED)
    }

    private fun handleRemoteCandidate(label: Int, id: String, strCandidate: String) {
        Log.i(TAG, "Got remote ICE candidate $strCandidate")
        executor.execute {
            val candidate = IceCandidate(id, label, strCandidate)
            peerConnection?.addIceCandidate(candidate)
        }
    }

    private fun setupMediaDevices() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val camera2 = Camera2Enumerator(context)
            if(camera2.deviceNames.isNotEmpty()) {
                val selectedDevice = camera2.deviceNames.firstOrNull(camera2::isFrontFacing) ?: camera2.deviceNames.first()
                videoCapturer = camera2.createCapturer(selectedDevice, null)
            }
        }
        if(videoCapturer == null) {
            val camera1 = Camera1Enumerator(true)
            val selectedDevice = camera1.deviceNames.firstOrNull(camera1::isFrontFacing) ?: camera1.deviceNames.first()
            videoCapturer = camera1.createCapturer(selectedDevice, null)
        }


        videoSource = factory?.createVideoSource(videoCapturer)

        videoCapturer?.startCapture(640, 480, 24)

        val stream = factory?.createLocalMediaStream(STREAM_LABEL)
        val videoTrack = factory?.createVideoTrack(VIDEO_TRACK_LABEL, videoSource)

        val videoRenderer = VideoRenderer(videoRenderers.localRenderer)
        videoTrack?.addRenderer(videoRenderer)
        stream?.addTrack(videoTrack)

        audioSource = factory?.createAudioSource(createAudioConstraints())
        val audioTrack = factory?.createAudioTrack(AUDIO_TRACK_LABEL, audioSource)

        stream?.addTrack(audioTrack)

        peerConnection?.addStream(stream)
    }

    private fun createAudioConstraints(): MediaConstraints {
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
        return audioConstraints
    }

    private fun handleRemoteDescriptor(sdp: String) {
        if(isOfferingPeer) {
            peerConnection?.setRemoteDescription(SDPSetCallback({ setError ->
                if(setError != null) {
                    Log.e(TAG, "setRemoteDescription failed: $setError")
                }
            }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } else {
            peerConnection?.setRemoteDescription(SDPSetCallback({ setError ->
                if(setError != null) {
                    Log.e(TAG, "setRemoteDescription failed: $setError")
                } else {
                    peerConnection?.createAnswer(SDPCreateCallback(this::createDescriptorCallback), MediaConstraints())
                }
            }), SessionDescription(SessionDescription.Type.OFFER, sdp))
        }
    }

    private fun createDescriptorCallback(result: SDPCreateResult) {
        when(result) {
            is SDPCreateSuccess -> {
                peerConnection?.setLocalDescription(SDPSetCallback({ setResult ->
                    Log.i(TAG, "SetLocalDescription: $setResult")
                }), result.descriptor)
                signaler.sendSDP(result.descriptor.description)
            }
            is SDPCreateFailure -> Log.e(TAG, "Error creating offer: ${result.reason}")
        }
    }

    private fun onMessage(message: ClientMessage) {
        when(message) {
            is MatchMessage -> {
                onStatusChangedListener(VideoCallStatus.CONNECTING)
                isOfferingPeer = message.offer
                start()
            }
            is SDPMessage -> {
                handleRemoteDescriptor(message.sdp)
            }
            is ICEMessage -> {
                handleRemoteCandidate(message.label, message.id, message.candidate)
            }
            is PeerLeft -> {
                onStatusChangedListener(VideoCallStatus.FINISHED)
            }
        }
    }


    fun terminate() {
        signaler.close()
        try {
            videoCapturer?.stopCapture()
        } catch (ex: Exception) { }

        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource?.dispose()

        peerConnection?.dispose()

        factory?.dispose()

        eglBase.release()
    }

    companion object {

        fun connect(context: Context, url: String, videoRenderers: VideoRenderers, callback: (VideoCallStatus) -> Unit) : VideoCallSession {
            val websocketHandler = SignalingWebSocket()
            val session = VideoCallSession(context, callback, websocketHandler, videoRenderers)
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            Log.i(TAG, "Connecting to $url")
            client.newWebSocket(request, websocketHandler)
            client.dispatcher().executorService().shutdown()
            return session
        }

        private val STREAM_LABEL = "remoteStream"
        private val VIDEO_TRACK_LABEL = "remoteVideoTrack"
        private val AUDIO_TRACK_LABEL = "remoteAudioTrack"
        private val TAG = "VideoCallSession"
        private val executor = Executors.newSingleThreadExecutor()
    }
}