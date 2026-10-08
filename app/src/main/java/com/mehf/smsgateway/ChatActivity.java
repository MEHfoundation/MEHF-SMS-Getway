package com.mehf.smsgateway;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

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
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private EditText etMessage;
    private Button btnSend, btnEndCall;
    private ImageButton btnVoiceCall, btnVideoCall;
    private LinearLayout chatListLayout;
    private ScrollView chatScrollView;
    private RelativeLayout videoCallContainer;
    private FirebaseFirestore db;
    
    // WebRTC Variables
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoCapturer videoCapturer;

    // Users (Dynamic Variables)
    private String currentUserDocId = ""; 
    private String currentUserName = "";
    private String targetUserId = ""; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = FirebaseFirestore.getInstance();
        
        // UI Elements
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        chatListLayout = findViewById(R.id.chatListLayout);
        chatScrollView = findViewById(R.id.chatScrollView);
        btnVoiceCall = findViewById(R.id.btnVoiceCall);
        btnVideoCall = findViewById(R.id.btnVideoCall);
        btnEndCall = findViewById(R.id.btnEndCall);
        videoCallContainer = findViewById(R.id.videoCallContainer);
        localVideoView = findViewById(R.id.localVideoView);
        remoteVideoView = findViewById(R.id.remoteVideoView);

        // 0. Get Logged In User & Target User Data from Intent
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null) {
            currentUserDocId = user.getEmail().split("@")[0]; // Email se ID nikal li
            currentUserName = currentUserDocId;
        }

        targetUserId = getIntent().getStringExtra("targetUserId");
        String targetName = getIntent().getStringExtra("targetUserName");
        
        if (targetUserId == null) targetUserId = "Unknown";
        setTitle("Chat: " + (targetName != null ? targetName : targetUserId));

        // 1. Text Chat Functions
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessageToFirebase(text);
                etMessage.setText("");
            }
        });
        listenForLiveMessages();

        // 2. WebRTC Initialization
        initWebRTC();

        // 3. Call Buttons Logic (Hide for AI & Groups)
        if (targetUserId.equals("AI") || targetUserId.startsWith("group_")) {
            btnVideoCall.setVisibility(View.GONE);
            btnVoiceCall.setVisibility(View.GONE);
        } else {
            btnVideoCall.setVisibility(View.VISIBLE);
            btnVoiceCall.setVisibility(View.VISIBLE);
            btnVideoCall.setOnClickListener(v -> startCall(true));
            btnVoiceCall.setOnClickListener(v -> startCall(false));
        }
        
        btnEndCall.setOnClickListener(v -> {
            endCall();
        });

        // 4. Listen for Incoming Calls & Signals
        listenForIncomingSignals();
    }

    // ================== CHAT SYSTEM ==================
    private void sendMessageToFirebase(String text) {
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("senderId", currentUserDocId);
        chatData.put("senderName", currentUserName);
        chatData.put("receiverId", targetUserId);
        chatData.put("message", text);
        chatData.put("status", "sent");
        chatData.put("timestamp", System.currentTimeMillis());
        db.collection("chats").add(chatData);
    }

    private void listenForLiveMessages() {
        db.collection("chats")
          .orderBy("timestamp", Query.Direction.ASCENDING)
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String msg = dc.getDocument().getString("message");
                      String senderId = dc.getDocument().getString("senderId");
                      String receiverId = dc.getDocument().getString("receiverId");
                      
                      // Sirf wahi message dikhayein jo is user aur target ke beech hain (ya broadcast hain)
                      if ((senderId.equals(currentUserDocId) && receiverId.equals(targetUserId)) || 
                          (senderId.equals(targetUserId) && receiverId.equals(currentUserDocId)) ||
                          (receiverId.equals(targetUserId) && targetUserId.startsWith("group_"))) {
                          
                          displayMessageOnScreen(msg, currentUserDocId.equals(senderId));
                      }
                  }
              }
          });
    }

    private void displayMessageOnScreen(String text, boolean isMe) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(16f); tv.setPadding(30, 20, 30, 20); tv.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 10);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(20);
        
        if (isMe) {
            params.gravity = Gravity.END; shape.setColor(Color.parseColor("#DCF8C6"));
        } else {
            params.gravity = Gravity.START; shape.setColor(Color.WHITE);
        }
        tv.setLayoutParams(params); tv.setBackground(shape); chatListLayout.addView(tv);
        chatScrollView.post(() -> chatScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // ================== WEBRTC SETUP & CAMERA ==================
    private void initWebRTC() {
        rootEglBase = EglBase.create();
        localVideoView.init(rootEglBase.getEglBaseContext(), null);
        remoteVideoView.init(rootEglBase.getEglBaseContext(), null);
        localVideoView.setZOrderMediaOverlay(true);
        localVideoView.setMirror(true);
        
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoDecoderFactory(new org.webrtc.DefaultVideoDecoderFactory(rootEglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new org.webrtc.DefaultVideoEncoderFactory(rootEglBase.getEglBaseContext(), true, true))
                .createPeerConnectionFactory();
    }

    private void startLocalStream(boolean isVideo) {
        if (isVideo) {
            videoCapturer = createVideoCapturer();
            if (videoCapturer != null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.getEglBaseContext());
                VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
                videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
                videoCapturer.startCapture(1024, 720, 30);

                localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
                localVideoTrack.addSink(localVideoView);
            }
        }
        
        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);
    }

    private VideoCapturer createVideoCapturer() {
        CameraEnumerator enumerator = Camera2Enumerator.isSupported(this) ? new Camera2Enumerator(this) : new Camera1Enumerator(true);
        for (String deviceName : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null);
            }
        }
        return null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()); // Google Free STUN

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                // Send ICE candidate to Firebase
                Map<String, Object> candidateData = new HashMap<>();
                candidateData.put("type", "candidate");
                candidateData.put("sdpMid", iceCandidate.sdpMid);
                candidateData.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidateData.put("sdp", iceCandidate.sdp);
                db.collection("calls").document(targetUserId).collection("candidates").add(candidateData);
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            
            @Override
            public void onAddStream(MediaStream mediaStream) {
                // Receive Remote Video
                if (mediaStream.videoTracks.size() > 0) {
                    runOnUiThread(() -> {
                        mediaStream.videoTracks.get(0).addSink(remoteVideoView);
                    });
                }
            }

            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
        });

        if (localVideoTrack != null) { peerConnection.addTrack(localVideoTrack); }
        if (localAudioTrack != null) { peerConnection.addTrack(localAudioTrack); }
    }

    // ================== CALLING & SIGNALING ==================
    private void startCall(boolean isVideo) {
        videoCallContainer.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Calling " + targetUserId + "...", Toast.LENGTH_SHORT).show();
        
        startLocalStream(isVideo);
        createPeerConnection();

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo ? "true" : "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                
                // Firebase Signaling: Create an 'Offer'
                Map<String, Object> callData = new HashMap<>();
                callData.put("callerId", currentUserDocId);
                callData.put("targetId", targetUserId);
                callData.put("type", "offer");
                callData.put("isVideo", isVideo);
                callData.put("sdp", sessionDescription.description);

                db.collection("calls").document(targetUserId).set(callData);
            }
        }, constraints);
    }

    private void listenForIncomingSignals() {
        // Listen for Offers or Answers
        db.collection("calls").document(currentUserDocId)
          .addSnapshotListener((snapshot, e) -> {
              if (e != null || snapshot == null || !snapshot.exists()) return;
              
              String type = snapshot.getString("type");
              String sdpStr = snapshot.getString("sdp");

              if ("offer".equals(type)) {
                  videoCallContainer.setVisibility(View.VISIBLE);
                  boolean isVideo = snapshot.getBoolean("isVideo") != null ? snapshot.getBoolean("isVideo") : true;
                  Toast.makeText(this, "Incoming Call...", Toast.LENGTH_LONG).show();
                  
                  startLocalStream(isVideo);
                  createPeerConnection();

                  peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, sdpStr));
                  
                  peerConnection.createAnswer(new SimpleSdpObserver() {
                      @Override
                      public void onCreateSuccess(SessionDescription sessionDescription) {
                          peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                          
                          Map<String, Object> answerData = new HashMap<>();
                          answerData.put("type", "answer");
                          answerData.put("sdp", sessionDescription.description);
                          db.collection("calls").document(snapshot.getString("callerId")).set(answerData);
                      }
                  }, new MediaConstraints());
                  
              } else if ("answer".equals(type)) {
                  peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.ANSWER, sdpStr));
              }
          });

        // Listen for ICE Candidates
        db.collection("calls").document(currentUserDocId).collection("candidates")
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String sdp = dc.getDocument().getString("sdp");
                      int sdpMLineIndex = dc.getDocument().getLong("sdpMLineIndex").intValue();
                      String sdpMid = dc.getDocument().getString("sdpMid");
                      
                      IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                      if (peerConnection != null) {
                          peerConnection.addIceCandidate(candidate);
                      }
                  }
              }
          });
    }

    private void endCall() {
        videoCallContainer.setVisibility(View.GONE);
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException e) { e.printStackTrace(); }
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        db.collection("calls").document(targetUserId).delete(); // Delete call data
        db.collection("calls").document(currentUserDocId).delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endCall();
        if (localVideoView != null) localVideoView.release();
        if (remoteVideoView != null) remoteVideoView.release();
    }

    // Helper class to make SDP observer code cleaner
    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) { Log.e("WebRTC", "SDP Create Error: " + s); }
        @Override public void onSetFailure(String s) { Log.e("WebRTC", "SDP Set Error: " + s); }
    }
}
