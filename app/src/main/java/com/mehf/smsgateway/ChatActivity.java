package com.mehf.smsgateway;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import org.webrtc.EglBase;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnection;

import java.util.HashMap;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private EditText etMessage;
    private Button btnSend, btnEndCall;
    private ImageButton btnVoiceCall, btnVideoCall;
    private LinearLayout chatListLayout;
    private ScrollView chatScrollView;
    private RelativeLayout videoCallContainer;
    private FirebaseFirestore db;
    
    // WebRTC Views
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;
    private EglBase rootEglBase;

    // Users (Testing IDs)
    private String currentUserDocId = "AppUser_" + System.currentTimeMillis(); 
    private String currentUserName = "App Admin";
    private String targetUserId = "WebUser123"; 

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

        // 1. Chat Functions
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

        // 3. Call Buttons
        btnVideoCall.setOnClickListener(v -> startCall(true));
        btnVoiceCall.setOnClickListener(v -> startCall(false));
        
        btnEndCall.setOnClickListener(v -> {
            videoCallContainer.setVisibility(View.GONE);
            // यहाँ Firebase से कॉल एन्ड करने का सिग्नल जाएगा
        });

        // 4. Listen for Incoming Calls (Firebase Signaling)
        listenForIncomingCalls();
    }

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
                      displayMessageOnScreen(msg, currentUserDocId.equals(senderId));
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

    // ================== WEBRTC & FIREBASE SIGNALING ==================
    private void initWebRTC() {
        rootEglBase = EglBase.create();
        localVideoView.init(rootEglBase.getEglBaseContext(), null);
        remoteVideoView.init(rootEglBase.getEglBaseContext(), null);
        localVideoView.setZOrderMediaOverlay(true);
        localVideoView.setMirror(true);
        
        // PeerConnectionFactory initialization (Standard WebRTC setup)
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
    }

    private void startCall(boolean isVideo) {
        videoCallContainer.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Calling " + targetUserId + "...", Toast.LENGTH_SHORT).show();
        
        // Firebase Signaling: Create an 'Offer'
        Map<String, Object> callData = new HashMap<>();
        callData.put("callerId", currentUserDocId);
        callData.put("targetId", targetUserId);
        callData.put("type", "offer");
        callData.put("isVideo", isVideo);
        callData.put("sdp", "SDP_OFFER_STRING_HERE"); // WebRTC Session Description

        db.collection("calls").document(targetUserId).set(callData);
    }

    private void listenForIncomingCalls() {
        // Firebase Signaling: Listen for 'Offer' or 'Answer'
        db.collection("calls").document(currentUserDocId)
          .addSnapshotListener((snapshot, e) -> {
              if (e != null || snapshot == null || !snapshot.exists()) return;
              
              String type = snapshot.getString("type");
              if ("offer".equals(type)) {
                  videoCallContainer.setVisibility(View.VISIBLE);
                  Toast.makeText(this, "Incoming Call...", Toast.LENGTH_LONG).show();
                  
                  // Firebase Signaling: Create an 'Answer'
                  Map<String, Object> answerData = new HashMap<>();
                  answerData.put("type", "answer");
                  answerData.put("sdp", "SDP_ANSWER_STRING_HERE");
                  db.collection("calls").document(snapshot.getString("callerId")).set(answerData);
              }
          });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (localVideoView != null) localVideoView.release();
        if (remoteVideoView != null) remoteVideoView.release();
    }
}
