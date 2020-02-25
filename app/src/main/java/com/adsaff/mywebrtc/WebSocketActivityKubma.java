package com.adsaff.mywebrtc;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.adsaff.mywebrtc.databinding.ActivityWebSocketKumbaBinding;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Set;

import io.socket.client.IO;
import io.socket.client.Socket;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

import static io.socket.client.Socket.EVENT_CONNECT;
import static io.socket.client.Socket.EVENT_DISCONNECT;
import static org.webrtc.SessionDescription.Type.ANSWER;
import static org.webrtc.SessionDescription.Type.OFFER;

public class WebSocketActivityKubma extends AppCompatActivity {
    private static final String TAG = WebSocketActivityKubma.class.getSimpleName();
    private static final int RC_CALL = 111;
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";

    public static final int VIDEO_RESOLUTION_WIDTH = 1280;
    public static final int VIDEO_RESOLUTION_HEIGHT = 720;
    public static final int FPS = 30;

    private Socket socket;
    private ActivityWebSocketKumbaBinding binding;
    private PeerConnection peerConnection;
    private EglBase rootEglBase;
    private PeerConnectionFactory factory;
    private VideoTrack videoTrackFromCamera;
    private AudioTrack localAudioTrack;
    private AudioSource audioSource;
    private MediaConstraints audioConstraints;
    private boolean enableAudio;
    private AppRTCAudioManager audioManager;
    private boolean onMicEnabled = true;
    String roomId = "33";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_web_socket_kumba);
        setSupportActionBar(binding.toolbar);

        roomId = getIntent().getStringExtra("room_id");
        start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @AfterPermissionGranted(RC_CALL)
    public void start() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (EasyPermissions.hasPermissions(this, perms)) {

            startAudioStreaming();

            initializeSurfaceViews();

            initializePeerConnectionFactory();

            createVideoTrackFromCameraAndShowIt();

            initializePeerConnections();

            connectToSignallingServer();

            startStreamingVideo();

            enableMic();


            binding.buttonCallDisconnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    finishVideoCall();
                }
            });

            binding.buttonCallToggleMic.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    localAudioTrack.setEnabled(true);
                    binding.buttonCallToggleMic.setAlpha(onToggleMic() ? 1.0f : 0.3f);
                }
            });
            binding.buttonCallSwitchCamera.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {

                }
            });


        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, perms);
        }
    }


    private boolean captureToTexture() {
        return true;
    }

    public void enableMic() {
        binding.buttonCallToggleMic.setAlpha(true ? 1.0f : 0.3f);
        localAudioTrack.setEnabled(true);
    }

    public void disableVideo() {
        videoTrackFromCamera.setEnabled(false);
    }

    public boolean onToggleMic() {
        if (onMicEnabled) {
            onMicEnabled = false;
        } else {
            onMicEnabled = true;
        }
        return onMicEnabled;
    }

    public void finishVideoCall() {
        if (peerConnection != null) {
            peerConnection.close();
        }
        if (socket != null) {
            socket.disconnect();
        }
        finish();
    }

    public void startAudioStreaming() {
        audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
        audioManager = AppRTCAudioManager.create(this);

        audioManager.start(new AppRTCAudioManager.AudioManagerEvents() {
            @Override
            public void onAudioDeviceChanged(AppRTCAudioManager.AudioDevice device, Set<AppRTCAudioManager.AudioDevice> availableDevices) {
                WebSocketActivityKubma.this.onAudioManagerDevicesChanged(device, availableDevices);
            }
        });
    }

    public void startAudioSwitch() {

    }

    private void onAudioManagerDevicesChanged(
            final AppRTCAudioManager.AudioDevice device, final Set<AppRTCAudioManager.AudioDevice> availableDevices) {
    }

    private AudioTrack createAudioTrack() {
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        localAudioTrack.setEnabled(enableAudio);
        return localAudioTrack;
    }

    private void connectToSignallingServer() {
        try {
            //socket = IO.socket("http://192.168.1.10:3000/");
            socket = IO.socket("https://videocall-webrtc-android.herokuapp.com/");
            socket.on(EVENT_CONNECT, args -> {
                Log.w(TAG, "connectToSignallingServer: Socket Server Connected. ");
                socket.emit("create or join", roomId);
            }).on("new peer", args -> {
                String peerId = (String) args[0];
                Log.w(TAG, "connectToSignallingServer: New peer added with peer id - " + peerId);
                offer(peerId);
            }).on("signal", (args) -> {
                try {
                    JSONObject signalObject = (JSONObject) args[0];
                    String type = signalObject.getString("type");
                    String peerId = signalObject.getString("peerId");

                    Log.w(TAG, "connectToSignallingServer: " + type + "  received with peer id -  " + peerId);
                    if (type.equalsIgnoreCase("OFFER")) {
                        peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, signalObject.getString("sdp")));
                        doAnswer(type, peerId);
                    } else if (type.equalsIgnoreCase("ANSWER")) {
                        peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(ANSWER, signalObject.getString("sdp")));
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }).on("signal candidate", (args) -> {
                Log.d(TAG, "connectToSignallingServer: receiving candidates" + args);
                try {
                    JSONObject signalObject = (JSONObject) args[0];
                    String type = signalObject.getString("type");
                    if (type.equals("candidate")) {
                        Log.d(TAG, "connectToSignallingServer: receiving candidates");
                        IceCandidate candidate = new IceCandidate(signalObject.getString("id"), signalObject.getInt("label"), signalObject.getString("candidate"));
                        peerConnection.addIceCandidate(candidate);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }).on(EVENT_DISCONNECT, args -> {
                Log.w(TAG, "connectToSignallingServer: disconnect");
            });
            socket.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

    }


    private void offer(String peerId) {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(
                new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    message.put("type", sessionDescription.type);
                    message.put("peerId", peerId);
                    message.put("sdp", sessionDescription.description);
                    socket.emit("signal", peerId, message);
                    Log.w(TAG, "connectToSignallingServer: Offer Emitted !!");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, sdpMediaConstraints);
    }

    private void doAnswer(String type, String peerId) {
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    message.put("type", "ANSWER");
                    message.put("peerId", peerId);
                    message.put("sdp", sessionDescription.description);
                    socket.emit("signal", peerId, message);
                    Log.w(TAG, "connectToSignallingServer: Answer Emitted !!");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new MediaConstraints());
    }


    private void initializeSurfaceViews() {
        rootEglBase = EglBase.create();
        binding.surfaceView.init(rootEglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(true);

        binding.surfaceView2.init(rootEglBase.getEglBaseContext(), null);
        binding.surfaceView2.setEnableHardwareScaler(true);
        binding.surfaceView2.setMirror(true);
    }


    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true);
        factory = new PeerConnectionFactory(null);
        factory.setVideoHwAccelerationOptions(rootEglBase.getEglBaseContext(), rootEglBase.getEglBaseContext());
    }

    private void createVideoTrackFromCameraAndShowIt() {
        VideoCapturer videoCapturer = createVideoCapturer();
        VideoSource videoSource = factory.createVideoSource(videoCapturer);
        videoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS);

        videoTrackFromCamera = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrackFromCamera.setEnabled(true);
        videoTrackFromCamera.addRenderer(new VideoRenderer(binding.surfaceView));
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer;
        if (useCamera2()) {
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    /*
     * Read more about Camera2 here
     * https://developer.android.com/reference/android/hardware/camera2/package-summary.html
     * */
    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

    private void initializePeerConnections() {
        peerConnection = createPeerConnection(factory);
    }

    private PeerConnection createPeerConnection(PeerConnectionFactory factory) {
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        MediaConstraints pcConstraints = new MediaConstraints();

        PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.w(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.w(TAG, "onIceConnectionChange: " + iceConnectionState);

            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.w(TAG, "onIceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.w(TAG, "onIceGatheringChange: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: ");
                JSONObject message = new JSONObject();

                try {
                    message.put("type", "candidate");
                    message.put("label", iceCandidate.sdpMLineIndex);
                    message.put("id", iceCandidate.sdpMid);
                    message.put("candidate", iceCandidate.sdp);
                    message.put("peerId", roomId);

                    Log.d(TAG, "onIceCandidate: sending candidate " + message);
                    socket.emit("candidate", message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.w(TAG, "onIceCandidatesRemoved: " + iceCandidates);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.w(TAG, "connectToSignallingServer:  media Stream Received :  " + mediaStream.videoTracks.size());
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                AudioTrack audioTrack = mediaStream.audioTracks.get(0);
                audioTrack.setEnabled(true);
                remoteVideoTrack.setEnabled(true);
                remoteVideoTrack.addRenderer(new VideoRenderer(binding.surfaceView2));


            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.w(TAG, "onRemoveStream: ");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.w(TAG, "onDataChannel: " + dataChannel);
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.w(TAG, "onRenegotiationNeeded: ");
            }
        };

        return factory.createPeerConnection(rtcConfig, pcConstraints, pcObserver);
    }


    private void startStreamingVideo() {
        MediaStream mediaStream = factory.createLocalMediaStream("ARDAMS");
        mediaStream.addTrack(videoTrackFromCamera);
        mediaStream.addTrack(createAudioTrack());
        Log.w(TAG, "connectToSignallingServer:   " + "Adding local media Stream");
        peerConnection.addStream(mediaStream);


    }

    @Override
    protected void onDestroy() {
        if (socket != null) {
            if (peerConnection != null) {
                peerConnection.close();
            }
            if (socket != null) {
                socket.disconnect();
            }
            socket.emit("disconnect");
        }
        super.onDestroy();
    }

}
