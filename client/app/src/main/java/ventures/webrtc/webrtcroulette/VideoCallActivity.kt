package ventures.webrtc.webrtcroulette

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import ventures.webrtc.webrtcroulette.videocall.VideoCallSession
import ventures.webrtc.webrtcroulette.videocall.VideoCallStatus
import ventures.webrtc.webrtcroulette.videocall.VideoSinks

class VideoCallActivity : AppCompatActivity() {

    private var videoSession : VideoCallSession? = null
    private var statusTextView: TextView? = null
    private var localVideoView: SurfaceViewRenderer? = null
    private var remoteVideoView: SurfaceViewRenderer? = null
    private var audioManager: AudioManager? = null
    private var savedMicrophoneState : Boolean? = null
    private var savedAudioMode: Int? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_call)
        statusTextView = findViewById(R.id.status_text)
        localVideoView = findViewById(R.id.pip_video)
        remoteVideoView = findViewById(R.id.remote_video)

        val hangup : ImageButton = findViewById(R.id.hangup_button)
        hangup.setOnClickListener {
            finish()
        }

        audioManager = this.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        savedAudioMode = audioManager?.mode
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

        savedMicrophoneState = audioManager?.isMicrophoneMute
        audioManager?.isMicrophoneMute = false

        handlePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()

        videoSession?.terminate()
        localVideoView?.release()
        remoteVideoView?.release()

        if(savedAudioMode !== null) {
            audioManager?.mode = savedAudioMode!!
        }
        if(savedMicrophoneState != null) {
            audioManager?.isMicrophoneMute = savedMicrophoneState!!
        }
    }

    private fun onStatusChanged(newStatus: VideoCallStatus) {
        Log.d(TAG,"New call status: $newStatus")
        runOnUiThread {
            when(newStatus) {
                VideoCallStatus.FINISHED -> finish()
                else -> {
                    statusTextView?.text = resources.getString(newStatus.label)
                    statusTextView?.setTextColor(ContextCompat.getColor(this, newStatus.color))
                }
            }
        }
    }

    private fun handlePermissions() {
        val canAccessCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val canRecordAudio  = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if(!canAccessCamera || !canRecordAudio) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), CAMERA_AUDIO_PERMISSION_REQUEST)
        } else {
            startVideoSession()
        }
    }

    private fun startVideoSession() {
        videoSession = VideoCallSession.connect(applicationContext, BACKEND_URL, VideoSinks(localVideoView, remoteVideoView), this::onStatusChanged)

        localVideoView?.init(videoSession?.renderContext, null)
        localVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        localVideoView?.setZOrderMediaOverlay(true)
        localVideoView?.setEnableHardwareScaler(true)

        remoteVideoView?.init(videoSession?.renderContext, null)
        remoteVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        remoteVideoView?.setEnableHardwareScaler(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        Log.w(TAG, "onRequestPermissionsResult: $requestCode $permissions $grantResults")
        when (requestCode) {
            CAMERA_AUDIO_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.first() == PackageManager.PERMISSION_GRANTED) {
                    startVideoSession()
                } else {
                    finish()
                }
                return
            }
        }
    }

    companion object {
        const val CAMERA_AUDIO_PERMISSION_REQUEST = 1
        const val TAG = "VideoCallActivity"
        const val BACKEND_URL = "ws://HOST:8000/" // Change HOST to your server's IP if you want to test
    }
}
