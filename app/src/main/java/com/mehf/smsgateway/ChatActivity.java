package com.mehf.smsgateway;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private Button btnSend, btnEndCall, btnAcceptCall, btnDeclineCall;
    private ImageButton btnVoiceCall, btnVideoCall;
    private LinearLayout chatListLayout, incomingCallLayout;
    private ScrollView chatScrollView;
    private RelativeLayout videoCallContainer;
    private TextView tvChatTitle, tvIncomingCallTitle;
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

    // Call Variables & Ringtone
    private String incomingCallerId = "";
    private String incomingSdp = "";
    private boolean isIncomingVideo = false;
    private MediaPlayer ringtonePlayer; // Ringtone System

    private String currentUserDocId = ""; 
    private String currentUserName = "";
    private String targetUserId = ""; 

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 100);
        }

        db = FirebaseFirestore.getInstance();
        
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
        
        incomingCallLayout = findViewById(R.id.incomingCallLayout);
        tvIncomingCallTitle = findViewById(R.id.tvIncomingCallTitle);
        btnAcceptCall = findViewById(R.id.btnAcceptCall);
        btnDeclineCall = findViewById(R.id.btnDeclineCall);
        tvChatTitle = findViewById(R.id.tvChatTitle);

        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && user.getEmail() != null) {
            currentUserDocId = user.getEmail().split("@")[0];
            currentUserName = currentUserDocId;
        } else if (user != null) {
            currentUserDocId = user.getUid();
        }

        targetUserId = getIntent().getStringExtra("targetUserId");
        String targetName = getIntent().getStringExtra("targetUserName");
        if (targetUserId == null) targetUserId = "Unknown";
        tvChatTitle.setText(targetName != null ? targetName : targetUserId);

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessageToFirebase(text);
                etMessage.setText("");
                if (targetUserId.equals("AI")) fetchAIReply(text);
            }
        });
        listenForLiveMessages();

        try { initWebRTC(); } catch (Exception e) { Log.e("WebRTC", "Init failed", e); }

        if (targetUserId.equals("AI") || targetUserId.startsWith("group_")) {
            btnVideoCall.setVisibility(View.GONE);
            btnVoiceCall.setVisibility(View.GONE);
        } else {
            btnVideoCall.setVisibility(View.VISIBLE);
            btnVoiceCall.setVisibility(View.VISIBLE);
            btnVideoCall.setOnClickListener(v -> startCall(true));
            btnVoiceCall.setOnClickListener(v -> startCall(false));
        }
        
        btnEndCall.setOnClickListener(v -> endCall());

        // ACCEPT CALL
        btnAcceptCall.setOnClickListener(v -> {
            stopRingtone(); // Stop Ringtone when picked up
            incomingCallLayout.setVisibility(View.GONE);
            videoCallContainer.setVisibility(View.VISIBLE);
            
            startLocalStream(isIncomingVideo);
            createPeerConnection();

            peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.OFFER, incomingSdp));
            
            peerConnection.createAnswer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                    Map<String, Object> answerData = new HashMap<>();
                    answerData.put("type", "answer");
                    answerData.put("sdp", sessionDescription.description);
                    db.collection("calls").document(incomingCallerId).set(answerData);
                }
            }, new MediaConstraints());
        });

        // DECLINE CALL
        btnDeclineCall.setOnClickListener(v -> {
            stopRingtone(); // Stop Ringtone
            incomingCallLayout.setVisibility(View.GONE);
            db.collection("calls").document(currentUserDocId).delete();
        });

        listenForIncomingSignals();
    }

    // ============ RINGTONE LOGIC ============
    private void startRingtone() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtonePlayer = MediaPlayer.create(this, ringtoneUri);
            ringtonePlayer.setLooping(true); // Continuous loop
            ringtonePlayer.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRingtone() {
        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
            ringtonePlayer.stop();
            ringtonePlayer.release();
            ringtonePlayer = null;
        }
    }
    // ========================================

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
        if (isMe) { params.gravity = Gravity.END; shape.setColor(Color.parseColor("#DCF8C6")); } 
        else { params.gravity = Gravity.START; shape.setColor(Color.WHITE); }
        tv.setLayoutParams(params); tv.setBackground(shape); chatListLayout.addView(tv);
        chatScrollView.post(() -> chatScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void fetchAIReply(String userText) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://api.groq.com/openai/v1/chat/completions");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer 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");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                org.json.JSONObject msgObj = new org.json.JSONObject();
                msgObj.put("role", "user");
                msgObj.put("content", "तुम स्मार्ट असिस्टेंट हो। सवाल: " + userText);

                org.json.JSONArray msgArray = new org.json.JSONArray();
                msgArray.put(msgObj);

                org.json.JSONObject jsonBody = new org.json.JSONObject();
                jsonBody.put("model", "llama-3.1-8b-instant");
                jsonBody.put("messages", msgArray);
                jsonBody.put("temperature", 0.3);

                conn.getOutputStream().write(jsonBody.toString().getBytes("UTF-8"));

                java.util.Scanner scanner = new java.util.Scanner(conn.getInputStream());
                StringBuilder response = new StringBuilder();
                while(scanner.hasNext()) response.append(scanner.nextLine());
                scanner.close();

                org.json.JSONObject jsonResponse = new org.json.JSONObject(response.toString());
                String aiReply = jsonResponse.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                Map<String, Object> aiChatData = new HashMap<>();
                aiChatData.put("senderId", "AI");
                aiChatData.put("senderName", "🤖 Smart AI Assistant");
                aiChatData.put("receiverId", currentUserDocId);
                aiChatData.put("message", aiReply);
                aiChatData.put("status", "sent");
                aiChatData.put("timestamp", System.currentTimeMillis());
                db.collection("chats").add(aiChatData);

            } catch (Exception e) { Log.e("AI_ERROR", "Error fetching AI response", e); }
        }).start();
    }

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
            if (enumerator.isFrontFacing(deviceName)) return enumerator.createCapturer(deviceName, null);
        }
        return null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Map<String, Object> candidateData = new HashMap<>();
                candidateData.put("type", "candidate");
                candidateData.put("sdpMid", iceCandidate.sdpMid);
                candidateData.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidateData.put("sdp", iceCandidate.sdp);
                String target = incomingCallerId.isEmpty() ? targetUserId : incomingCallerId;
                db.collection("calls").document(target).collection("candidates").add(candidateData);
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override
            public void onAddStream(MediaStream mediaStream) {
                if (mediaStream.videoTracks.size() > 0) runOnUiThread(() -> mediaStream.videoTracks.get(0).addSink(remoteVideoView));
            }
            @Override public void onRemoveStream(MediaStream mediaStream) {}
            @Override public void onDataChannel(DataChannel dataChannel) {}
            @Override public void onRenegotiationNeeded() {}
        });

        if (localVideoTrack != null) peerConnection.addTrack(localVideoTrack);
        if (localAudioTrack != null) peerConnection.addTrack(localAudioTrack);
    }

    private void startCall(boolean isVideo) {
        videoCallContainer.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Calling...", Toast.LENGTH_SHORT).show();
        startLocalStream(isVideo);
        createPeerConnection();

        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", isVideo ? "true" : "false"));

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
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
        db.collection("calls").document(currentUserDocId)
          .addSnapshotListener((snapshot, e) -> {
              // 🔴 If Caller Ends Call Before We Answer: Hide popup & Stop Ringtone
              if (snapshot == null || !snapshot.exists()) {
                  runOnUiThread(() -> {
                      incomingCallLayout.setVisibility(View.GONE);
                      stopRingtone();
                  });
                  return;
              }
              
              String type = snapshot.getString("type");
              String sdpStr = snapshot.getString("sdp");

              if ("offer".equals(type)) {
                  incomingCallerId = snapshot.getString("callerId");
                  isIncomingVideo = snapshot.getBoolean("isVideo") != null ? snapshot.getBoolean("isVideo") : true;
                  incomingSdp = sdpStr;
                  
                  // Show Call UI & START RINGTONE
                  incomingCallLayout.setVisibility(View.VISIBLE);
                  tvIncomingCallTitle.setText((isIncomingVideo ? "📹 Video Call" : "📞 Voice Call") + "\nFrom: " + incomingCallerId);
                  startRingtone();
                  
              } else if ("answer".equals(type)) {
                  peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(SessionDescription.Type.ANSWER, sdpStr));
              }
          });

        db.collection("calls").document(currentUserDocId).collection("candidates")
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String sdp = dc.getDocument().getString("sdp");
                      int sdpMLineIndex = dc.getDocument().getLong("sdpMLineIndex").intValue();
                      String sdpMid = dc.getDocument().getString("sdpMid");
                      IceCandidate candidate = new IceCandidate(sdpMid, sdpMLineIndex, sdp);
                      if (peerConnection != null) peerConnection.addIceCandidate(candidate);
                  }
              }
          });
    }

    private void endCall() {
        stopRingtone(); // Stop ringtone if active
        videoCallContainer.setVisibility(View.GONE);
        incomingCallLayout.setVisibility(View.GONE);
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException e) {}
            videoCapturer.dispose(); videoCapturer = null;
        }
        if (surfaceTextureHelper != null) { surfaceTextureHelper.dispose(); surfaceTextureHelper = null; }
        String target = incomingCallerId.isEmpty() ? targetUserId : incomingCallerId;
        db.collection("calls").document(target).delete(); 
        db.collection("calls").document(currentUserDocId).delete();
        incomingCallerId = "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        endCall();
        if (localVideoView != null) localVideoView.release();
        if (remoteVideoView != null) remoteVideoView.release();
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
