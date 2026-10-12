package com.mehf.smsgateway;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

// 🔥 NEW: ZegoCloud Imports
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton;
import com.zegocloud.uikit.service.defines.ZegoUIKitUser;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    // Removed Jitsi, Ringtone, and manual signaling variables as ZegoCloud handles them.
    private EditText etMessage;
    private Button btnSend;
    private ZegoSendCallInvitationButton btnVoiceCall, btnVideoCall; // 🔥 Updated to Zego Buttons
    private LinearLayout chatListLayout;
    private ScrollView chatScrollView;
    private TextView tvChatTitle;
    private FirebaseFirestore db;
    
    private boolean isActivityVisible = false; 

    private String currentUserDocId = ""; 
    private String currentUserName = "";
    private String targetUserId = ""; 
    private String chatSchoolId = "NA"; 

    // Used for logging call history
    private long callStartTime = 0;
    private boolean isCaller = false;

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
        tvChatTitle = findViewById(R.id.tvChatTitle);
        
        // 🔥 Link ZegoCloud specific buttons
        btnVoiceCall = findViewById(R.id.btnVoiceCall);
        btnVideoCall = findViewById(R.id.btnVideoCall);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
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
            
            // 🔥 NAYA: ZegoCloud Invitation Configuration
            // You must set the resourceID identical to the one you configured in the ZegoCloud Admin Console for offline calls.
            
            // Setup Voice Call Button
            btnVoiceCall.setIsVideoCall(false);
            btnVoiceCall.setResourceID("zego_uikit_call"); 
            btnVoiceCall.setInvitees(Collections.singletonList(new ZegoUIKitUser(targetUserId, targetName)));
            btnVoiceCall.setOnClickListener(v -> {
                 isCaller = true;
                 callStartTime = System.currentTimeMillis();
            });

            // Setup Video Call Button
            btnVideoCall.setIsVideoCall(true);
            btnVideoCall.setResourceID("zego_uikit_call");
            btnVideoCall.setInvitees(Collections.singletonList(new ZegoUIKitUser(targetUserId, targetName)));
            btnVideoCall.setOnClickListener(v -> {
                 isCaller = true;
                 callStartTime = System.currentTimeMillis();
            });
        }

        // 🔥 NAYA: Call History Logging using ZegoCloud Broadcasts
        BroadcastReceiver callEndReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // ZegoCloud fires this action when a call ends
                if ("com.zegocloud.uikit.call.ended".equals(intent.getAction())) {
                    if (isCaller && callStartTime > 0) {
                        long duration = System.currentTimeMillis() - callStartTime;
                        long mins = (duration / 1000) / 60;
                        long secs = (duration / 1000) % 60;
                        String timeStr = mins > 0 ? mins + " min " + secs + " sec" : secs + " sec";
                        // Determine call type based on intent extras if available, default to generic "Call" if not
                        boolean isVideo = intent.getBooleanExtra("isVideoCall", false);
                        String msg = (isVideo ? "📹 Video Call" : "📞 Voice Call") + " (Duration: " + timeStr + ")";

                        Map<String, Object> chatData = new HashMap<>();
                        chatData.put("senderId", currentUserDocId);
                        chatData.put("senderName", currentUserName);
                        chatData.put("receiverId", targetUserId);
                        chatData.put("message", msg);
                        chatData.put("status", "read"); 
                        chatData.put("schoolId", chatSchoolId); 
                        chatData.put("timestamp", System.currentTimeMillis());
                        db.collection("chats").add(chatData);
                    }
                    callStartTime = 0;
                    isCaller = false;
                }
            }
        };
        
        // Register receiver for ZegoCloud end call event
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.zegocloud.uikit.call.ended");
        LocalBroadcastManager.getInstance(this).registerReceiver(callEndReceiver, filter);
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
}
