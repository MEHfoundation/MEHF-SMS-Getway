package com.mehf.smsgateway;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import org.jitsi.meet.sdk.JitsiMeetActivity;
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private EditText etMessage;
    private Button btnSend, btnAcceptCall, btnDeclineCall;
    private ImageButton btnVoiceCall, btnVideoCall;
    private LinearLayout chatListLayout, incomingCallLayout;
    private ScrollView chatScrollView;
    private TextView tvChatTitle, tvIncomingCallTitle;
    private FirebaseFirestore db;
    
    private String incomingCallerId = "";
    private String incomingRoomName = "";
    private boolean isIncomingVideo = false;
    private MediaPlayer ringtonePlayer; 
    
    private boolean isCallActive = false; 
    private boolean isActivityVisible = false; 

    private String currentUserDocId = ""; 
    private String currentUserName = "";
    private String targetUserId = ""; 
    private String chatSchoolId = "NA"; 

    @Override
    protected void onResume() {
        super.onResume();
        isActivityVisible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityVisible = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null) km.requestDismissKeyguard(this, null);
            }
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        } catch (Exception e) {}

        setContentView(R.layout.activity_chat);
        db = FirebaseFirestore.getInstance();
        
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        chatListLayout = findViewById(R.id.chatListLayout);
        chatScrollView = findViewById(R.id.chatScrollView);
        btnVoiceCall = findViewById(R.id.btnVoiceCall);
        btnVideoCall = findViewById(R.id.btnVideoCall);
        
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
            currentUserName = "User";
        }
        if (currentUserDocId == null || currentUserDocId.isEmpty()) {
            currentUserDocId = "TempUser_" + System.currentTimeMillis();
        }

        targetUserId = getIntent().getStringExtra("targetUserId");
        String targetName = getIntent().getStringExtra("targetUserName");
        chatSchoolId = getIntent().getStringExtra("schoolId"); 
        if (chatSchoolId == null) chatSchoolId = "NA";
        if (targetUserId == null || targetUserId.isEmpty()) targetUserId = "Unknown";
        
        tvChatTitle.setText(targetName != null ? targetName : targetUserId);

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessageToFirebase(text);
                etMessage.setText("");
            }
        });
        
        listenForLiveMessages();

        if (targetUserId.equals("AI") || targetUserId.startsWith("group_")) {
            btnVideoCall.setVisibility(View.GONE);
            btnVoiceCall.setVisibility(View.GONE);
        } else {
            btnVideoCall.setVisibility(View.VISIBLE);
            btnVoiceCall.setVisibility(View.VISIBLE);
            btnVideoCall.setOnClickListener(v -> startCall(true));
            btnVoiceCall.setOnClickListener(v -> startCall(false));
        }
        
        btnAcceptCall.setOnClickListener(v -> { 
            stopRingtone(); 
            incomingCallLayout.setVisibility(View.GONE);
            db.collection("calls").document(currentUserDocId).update("type", "in-progress");
            launchJitsiMeet(incomingRoomName, isIncomingVideo);
        });
        
        btnDeclineCall.setOnClickListener(v -> { 
            stopRingtone();
            incomingCallLayout.setVisibility(View.GONE);
            db.collection("calls").document(currentUserDocId).delete();
        });

        listenForIncomingSignals();
    }

    private void sendMessageToFirebase(String text) {
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("senderId", currentUserDocId);
        chatData.put("senderName", currentUserName);
        chatData.put("receiverId", targetUserId);
        chatData.put("message", text);
        chatData.put("status", "sent"); 
        chatData.put("schoolId", chatSchoolId); 
        chatData.put("timestamp", System.currentTimeMillis());
        db.collection("chats").add(chatData);
    }

    private void listenForLiveMessages() {
        db.collection("chats")
          .orderBy("timestamp", Query.Direction.ASCENDING)
          .limitToLast(50) 
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  String docId = dc.getDocument().getId();
                  String msg = dc.getDocument().getString("message");
                  String senderId = dc.getDocument().getString("senderId");
                  String receiverId = dc.getDocument().getString("receiverId");
                  String status = dc.getDocument().getString("status");
                  Long timeObj = dc.getDocument().getLong("timestamp");
                  long timestamp = timeObj != null ? timeObj : System.currentTimeMillis();

                  if ((senderId.equals(currentUserDocId) && receiverId.equals(targetUserId)) || 
                      (senderId.equals(targetUserId) && receiverId.equals(currentUserDocId)) ||
                      (receiverId.equals(targetUserId) && targetUserId.startsWith("group_"))) {
                      
                      if (dc.getType() == DocumentChange.Type.ADDED) {
                          if (!senderId.equals(currentUserDocId)) {
                              if (isActivityVisible && !"read".equals(status)) {
                                  db.collection("chats").document(docId).update("status", "read");
                              }
                          }
                          displayMessageOnScreen(docId, msg, currentUserDocId.equals(senderId), status, timestamp);
                      } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                          updateMessageTickUI(docId, status);
                      }
                  }
              }
          });
    }

    private void displayMessageOnScreen(String docId, String text, boolean isMe, String status, long timestamp) {
        LinearLayout msgContainer = new LinearLayout(this);
        msgContainer.setOrientation(LinearLayout.VERTICAL);
        msgContainer.setPadding(30, 20, 30, 20);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 10);
        GradientDrawable shape = new GradientDrawable(); 
        shape.setCornerRadius(20);
        
        if (isMe) { 
            params.gravity = Gravity.END; 
            shape.setColor(Color.parseColor("#DCF8C6")); 
        } else { 
            params.gravity = Gravity.START; 
            shape.setColor(Color.WHITE); 
        }
        msgContainer.setLayoutParams(params); 
        msgContainer.setBackground(shape);

        TextView tvMsg = new TextView(this);
        tvMsg.setText(text); 
        tvMsg.setTextSize(16f); 
        tvMsg.setTextColor(Color.BLACK);
        msgContainer.addView(tvMsg);

        TextView tvTimeTick = new TextView(this);
        String timeStr = new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(timestamp));
        tvTimeTick.setTextSize(10f);
        tvTimeTick.setGravity(Gravity.END);
        tvTimeTick.setTag("tick_" + docId); 
        
        if (isMe) {
            boolean isRead = "read".equals(status);
            boolean isDelivered = "delivered".equals(status);
            String tick = isRead ? " ✓✓" : (isDelivered ? " ✓✓" : " ✓"); 
            tvTimeTick.setText(timeStr + tick);
            tvTimeTick.setTextColor(isRead ? Color.parseColor("#4CAF50") : Color.GRAY); 
        } else {
            tvTimeTick.setText(timeStr);
            tvTimeTick.setTextColor(Color.GRAY);
        }
        
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        timeParams.gravity = Gravity.END;
        timeParams.setMargins(0, 5, 0, 0);
        tvTimeTick.setLayoutParams(timeParams);
        
        msgContainer.addView(tvTimeTick);
        chatListLayout.addView(msgContainer);
        
        chatScrollView.post(() -> chatScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void updateMessageTickUI(String docId, String status) {
        TextView tvTick = chatListLayout.findViewWithTag("tick_" + docId);
        if (tvTick != null) {
            String currentText = tvTick.getText().toString().replace("✓✓", "").replace("✓", "").trim();
            if ("read".equals(status)) {
                tvTick.setText(currentText + " ✓✓");
                tvTick.setTextColor(Color.parseColor("#4CAF50")); 
            } else if ("delivered".equals(status)) {
                tvTick.setText(currentText + " ✓✓");
                tvTick.setTextColor(Color.GRAY);
            }
        }
    }

    private void startCall(boolean isVideo) {
        String roomName = "MEHF_Call_" + currentUserDocId + "_" + targetUserId;
        
        // Firebase me call ki request bhejein
        Map<String, Object> callData = new HashMap<>();
        callData.put("callerId", currentUserDocId);
        callData.put("targetId", targetUserId);
        callData.put("type", "jitsi_offer");
        callData.put("roomName", roomName);
        callData.put("isVideo", isVideo);
        callData.put("timestamp", System.currentTimeMillis());
        db.collection("calls").document(targetUserId).set(callData);

        // Native Jitsi Meeting Shuru karein
        launchJitsiMeet(roomName, isVideo);
    }

    private void launchJitsiMeet(String roomName, boolean isVideo) {
        try {
            JitsiMeetConferenceOptions options = new JitsiMeetConferenceOptions.Builder()
                    .setServerURL(new URL("https://meet.jit.si"))
                    .setRoom(roomName)
                    .setAudioMuted(!isVideo) 
                    .setVideoMuted(!isVideo) 
                    .build();
            JitsiMeetActivity.launch(this, options);
        } catch (Exception e) {
            Toast.makeText(this, "Call Start Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void listenForIncomingSignals() {
        db.collection("calls").document(currentUserDocId)
          .addSnapshotListener((snapshot, e) -> {
              if (isCallActive) return;

              if (snapshot == null || !snapshot.exists()) {
                  runOnUiThread(() -> {
                      incomingCallLayout.setVisibility(View.GONE);
                      stopRingtone();
                  });
                  return;
              }
              
              String type = snapshot.getString("type");
              
              if ("jitsi_offer".equals(type)) {
                  incomingCallerId = snapshot.getString("callerId");
                  incomingRoomName = snapshot.getString("roomName");
                  isIncomingVideo = snapshot.getBoolean("isVideo") != null ? snapshot.getBoolean("isVideo") : true;
                  
                  if (!isCallActive) {
                      incomingCallLayout.setVisibility(View.VISIBLE);
                      tvIncomingCallTitle.setText((isIncomingVideo ? "📹 Video Call" : "📞 Voice Call") + "\nFrom: " + incomingCallerId);
                      startRingtone();
                  }
              }
          });
    }

    private void startRingtone() {
        try {
            if (isCallActive) return; 
            if (ringtonePlayer != null && ringtonePlayer.isPlaying()) return; 
            
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtonePlayer = MediaPlayer.create(this, ringtoneUri);
            ringtonePlayer.setLooping(true); 
            ringtonePlayer.start();
        } catch (Exception e) {}
    }

    private void stopRingtone() {
        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
            ringtonePlayer.stop();
            ringtonePlayer.release();
            ringtonePlayer = null;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isCallActive) stopRingtone();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingtone();
    }
}
