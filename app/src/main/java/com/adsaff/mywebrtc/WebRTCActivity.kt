package com.adsaff.mywebrtc

import android.Manifest
import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import com.adsaff.mywebrtc.AppRTCAudioManager.AudioManagerEvents
import com.adsaff.mywebrtc.WebRTCActivity
import com.adsaff.mywebrtc.databinding.ActivityWebSocketBinding
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.*
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.net.URISyntaxException
import java.util.*


class WebRTCActivity : AppCompatActivity() {
    private var socket: Socket? = null
    private var binding: ActivityWebSocketBinding? = null
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoTrackFromCamera: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var audioConstraints: MediaConstraints? = null
    private val enableAudio = false
    private var audioManager: AppRTCAudioManager? = null
    private var onMicEnabled = true
    private var roomId: String? = null
    private var progressDialog: ProgressDialog? = null
    private var videoCapturer: VideoCapturer? = null
    private var localDataChannel: DataChannel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_web_socket)
        setSupportActionBar(binding!!.toolbar)
        progressDialog = ProgressDialog(this@WebRTCActivity)
        progressDialog!!.setMessage("Calling.... \nPlease wait. another person will connect soon.")
        progressDialog!!.setCancelable(true)
        roomId = intent.getStringExtra("room_id")
        start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    @AfterPermissionGranted(RC_CALL)
    fun start() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (EasyPermissions.hasPermissions(this, *perms)) {
            startAudioStreaming()
            initializeSurfaceViews()
            initializePeerConnectionFactory()
            createVideoTrackFromCameraAndShowIt()
            initializePeerConnections()
            connectToSignallingServer()
            startStreamingVideo()
            enableMic()
            binding!!.buttonCallDisconnect.setOnClickListener { finishVideoCall() }
            binding!!.buttonCallToggleMic.setOnClickListener {
                localAudioTrack!!.setEnabled(true)
                binding!!.buttonCallToggleMic.alpha = if (onToggleMic()) 1.0f else 0.3f
            }
            binding!!.buttonCallSwitchCamera.setOnClickListener { switchCamera() }
        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, *perms)
        }
    }

    private fun captureToTexture(): Boolean {
        return true
    }

    fun enableMic() {
        binding!!.buttonCallToggleMic.alpha = if (true) 1.0f else 0.3f
        localAudioTrack!!.setEnabled(true)
    }

    fun disableVideo() {
        videoTrackFromCamera!!.setEnabled(false)
    }

    fun onToggleMic(): Boolean {
        onMicEnabled = if (onMicEnabled) {
            false
        } else {
            true
        }
        return onMicEnabled
    }

    fun finishVideoCall() {
        if (peerConnection != null) {
            peerConnection!!.close()
        }
        if (socket != null) {
            socket!!.disconnect()
        }
    }

    fun startAudioStreaming() {
        audioConstraints = MediaConstraints()
        audioConstraints!!.mandatory.add(
                MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"))
        audioManager = AppRTCAudioManager.create(this)
        audioManager!!.start(AudioManagerEvents { device, availableDevices -> onAudioManagerDevicesChanged(device, availableDevices) })
    }

    private fun onAudioManagerDevicesChanged(
            device: AppRTCAudioManager.AudioDevice, availableDevices: Set<AppRTCAudioManager.AudioDevice>) {
    }

    private fun createAudioTrack(): AudioTrack? {
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack!!.setEnabled(enableAudio)
        return localAudioTrack
    }

    private fun connectToSignallingServer() {
        progressDialog!!.show()
        try {
            socket = IO.socket("https://videocall-webrtc-android.herokuapp.com/")
            socket!!.on(Socket.EVENT_CONNECT, Emitter.Listener { args: Array<Any?>? ->
                Log.w(TAG, "connectToSignallingServer: Socket Server Connected. ")
                socket!!.emit("create or join", roomId)
            }).on("new peer") { args: Array<Any> ->
                val peerId = args[0] as String
                Log.w(TAG, "connectToSignallingServer: New peer added with peer id - $peerId")
                offer(peerId)
            }.on("signal") { args: Array<Any> ->
                try {
                    val signalObject = args[0] as JSONObject
                    val type = signalObject.getString("type")
                    val peerId = signalObject.getString("peerId")
                    Log.w(TAG, "connectToSignallingServer: $type  received with peer id -  $peerId")
                    if (type.equals("OFFER", ignoreCase = true)) {
                        peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.OFFER, signalObject.getString("sdp")))
                        doAnswer(type, peerId)
                    } else if (type.equals("ANSWER", ignoreCase = true)) {
                        peerConnection!!.setRemoteDescription(SimpleSdpObserver(), SessionDescription(SessionDescription.Type.ANSWER, signalObject.getString("sdp")))
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }.on("signal candidate") { args: Array<Any> ->
                Log.w(TAG, "connectToSignallingServer: receiving candidates$args")
                progressDialog!!.dismiss()
                try {
                    val signalObject = args[0] as JSONObject
                    val type = signalObject.getString("type")
                    if (type == "candidate") {
                        Log.w(TAG, "connectToSignallingServer: receiving candidates")
                        val candidate = IceCandidate(signalObject.getString("id"), signalObject.getInt("label"), signalObject.getString("candidate"))
                        peerConnection!!.addIceCandidate(candidate)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }.on(Socket.EVENT_DISCONNECT) { args: Array<Any?>? ->
                Log.w(TAG, "connectToSignallingServer: disconnect")
                finish()
            }
            socket!!.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    private fun offer(peerId: String) {
        val sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints.mandatory.add(
                MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        peerConnection!!.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", sessionDescription.type)
                    message.put("peerId", peerId)
                    message.put("sdp", sessionDescription.description)
                    socket!!.emit("signal", peerId, message)
                    Log.w(TAG, "connectToSignallingServer: Offer Emitted !!")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, sdpMediaConstraints)
    }

    private fun doAnswer(type: String, peerId: String) {
        peerConnection!!.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection!!.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                val message = JSONObject()
                try {
                    message.put("type", "ANSWER")
                    message.put("peerId", peerId)
                    message.put("sdp", sessionDescription.description)
                    socket!!.emit("signal", peerId, message)
                    Log.w(TAG, "connectToSignallingServer: Answer Emitted !!")
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
        }, MediaConstraints())
    }

    private fun initializeSurfaceViews() {
        rootEglBase = EglBase.create()
        binding!!.surfaceView.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.surfaceView.setEnableHardwareScaler(true)
        binding!!.surfaceView.setMirror(true)
        binding!!.surfaceView2.init(rootEglBase!!.getEglBaseContext(), null)
        binding!!.surfaceView2.setEnableHardwareScaler(true)
        binding!!.surfaceView2.setMirror(true)
    }

    private fun initializePeerConnectionFactory() {
        PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true)
        factory = PeerConnectionFactory(null)
        factory!!.setVideoHwAccelerationOptions(rootEglBase!!.eglBaseContext, rootEglBase!!.eglBaseContext)
    }

    private fun createVideoTrackFromCameraAndShowIt() {
        videoCapturer = createVideoCapturer()
        val videoSource = factory!!.createVideoSource(videoCapturer)
        videoCapturer!!.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS)
        videoTrackFromCamera = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrackFromCamera!!.setEnabled(true)
        videoTrackFromCamera!!.addRenderer(VideoRenderer(binding!!.surfaceView))
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer?
        videoCapturer = if (useCamera2()) {
            createCameraCapturer(Camera2Enumerator(this))
        } else {
            createCameraCapturer(Camera1Enumerator(true))
        }
        return videoCapturer
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }

    /*
     * Read more about Camera2 here
     * https://developer.android.com/reference/android/hardware/camera2/package-summary.html
     * */
    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this)
    }

    private fun initializePeerConnections() {
        peerConnection = createPeerConnection(factory)
    }

    fun switchCamera() {
        val cameraVideoCapturer = videoCapturer as CameraVideoCapturer
        cameraVideoCapturer.switchCamera(null)
    }

    private fun createPeerConnection(factory: PeerConnectionFactory?): PeerConnection {
        val iceServers = ArrayList<IceServer>()
        iceServers.add(IceServer("stun:stun.l.google.com:19302"))
        val rtcConfig = RTCConfiguration(iceServers)
        val pcConstraints = MediaConstraints()
        val pcObserver: PeerConnection.Observer = object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: SignalingState) {
                Log.w(TAG, "onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                Log.w(TAG, "onIceConnectionChange: $iceConnectionState")
            }

            override fun onIceConnectionReceivingChange(b: Boolean) {
                Log.w(TAG, "onIceConnectionReceivingChange: $b")
            }

            override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                Log.w(TAG, "onIceGatheringChange: $iceGatheringState")
            }

            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.w(TAG, "onIceCandidate: ")
                val message = JSONObject()
                try {
                    message.put("type", "candidate")
                    message.put("label", iceCandidate.sdpMLineIndex)
                    message.put("id", iceCandidate.sdpMid)
                    message.put("candidate", iceCandidate.sdp)
                    message.put("peerId", roomId)
                    Log.w(TAG, "onIceCandidate: sending candidate $message")
                    socket!!.emit("candidate", message)
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }

            override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {
                Log.w(TAG, "onIceCandidatesRemoved: $iceCandidates")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                Log.w(TAG, "connectToSignallingServer:  media Stream Received :  " + mediaStream.videoTracks.size)
                val remoteVideoTrack = mediaStream.videoTracks[0]
                val audioTrack = mediaStream.audioTracks[0]
                Log.w(TAG, "connectToSignallingServer:  Audio Stream Received :  " + mediaStream.audioTracks[0])
                audioTrack.setEnabled(false)
                remoteVideoTrack.setEnabled(true)
                remoteVideoTrack.addRenderer(VideoRenderer(binding!!.surfaceView2))
            }

            override fun onRemoveStream(mediaStream: MediaStream) {
                Log.w(TAG, "onRemoveStream: ")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.w(TAG, "onDataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                Log.w(TAG, "onRenegotiationNeeded: ")
            }
        }
        return factory!!.createPeerConnection(rtcConfig, pcConstraints, pcObserver)
    }

    private fun startStreamingVideo() {
        val mediaStream = factory!!.createLocalMediaStream("ARDAMS")
        mediaStream.addTrack(videoTrackFromCamera)
        mediaStream.addTrack(createAudioTrack())
        Log.w(TAG, "connectToSignallingServer:   " + "Adding local media Stream")
        peerConnection!!.addStream(mediaStream)
        localDataChannel = peerConnection!!.createDataChannel("sendDataChannel", DataChannel.Init())
        localDataChannel!!.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {
                Log.d(TAG, "onStateChange: " + localDataChannel!!.state().toString())
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    Log.d(TAG, "Received_binary msg over $localDataChannel")
                    return
                }
                val data = buffer.data
                val bytes = ByteArray(data.capacity())
                data[bytes]
                val strData = String(bytes)
                Log.d(TAG, "Got_msg: $strData over $localDataChannel")
            }
        })
    }

    override fun onDestroy() {
        if (socket != null) {
            if (peerConnection != null) {
                peerConnection!!.close()
            }
            if (socket != null) {
                socket!!.disconnect()
            }
            socket!!.emit("disconnect")
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = WebRTCActivity::class.java.simpleName
        private const val RC_CALL = 111
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        const val AUDIO_TRACK_ID = "ARDAMSa0"
        private const val AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl"
        const val VIDEO_RESOLUTION_WIDTH = 1280
        const val VIDEO_RESOLUTION_HEIGHT = 720
        const val FPS = 30
    }
}