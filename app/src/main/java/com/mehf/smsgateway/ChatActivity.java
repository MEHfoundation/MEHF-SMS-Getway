package com.mehf.smsgateway;

import android.app.Application;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationConfig;
import com.zegocloud.uikit.prebuilt.call.invite.ZegoUIKitPrebuiltCallInvitationService;
import com.zegocloud.uikit.prebuilt.call.invite.widget.ZegoSendCallInvitationButton;
import com.zegocloud.uikit.service.defines.ZegoUIKitUser;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private EditText etMessage;
    private Button btnSend;
    private LinearLayout chatListLayout;
    private ScrollView chatScrollView;
    private FirebaseFirestore db;
    
    // अभी के लिए टेस्टिंग ID
    private String currentUserDocId = "AppUser_" + System.currentTimeMillis(); 
    private String currentUserName = "App Admin";
    private String targetUserId = "WebUser123"; // जिसको कॉल/मैसेज करना है

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = FirebaseFirestore.getInstance();
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        chatListLayout = findViewById(R.id.chatListLayout);
        chatScrollView = findViewById(R.id.chatScrollView);

        // 1. मैसेज भेजने का बटन
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessageToFirebase(text);
                etMessage.setText("");
            }
        });

        // 2. लाइव मैसेज मंगाने का फंक्शन
        listenForLiveMessages();

        // 3. कॉलिंग सिस्टम चालू करें
        initZegoCloudCalling();
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
        // Firebase से लाइव चैट मंगाना (पुराने मैसेज पहले, नए बाद में)
        db.collection("chats")
          .orderBy("timestamp", Query.Direction.ASCENDING)
          .addSnapshotListener((snapshots, e) -> {
              if (e != null || snapshots == null) return;
              
              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String msg = dc.getDocument().getString("message");
                      String senderId = dc.getDocument().getString("senderId");
                      
                      boolean isMe = currentUserDocId.equals(senderId);
                      displayMessageOnScreen(msg, isMe);
                  }
              }
          });
    }

    private void displayMessageOnScreen(String text, boolean isMe) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16f);
        tv.setPadding(30, 20, 30, 20);
        tv.setTextColor(Color.BLACK);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 10, 0, 10);
        
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(20);

        if (isMe) {
            // खुद का भेजा मैसेज (Right Side - हरा)
            params.gravity = Gravity.END;
            shape.setColor(Color.parseColor("#DCF8C6"));
        } else {
            // सामने वाले का मैसेज (Left Side - सफ़ेद)
            params.gravity = Gravity.START;
            shape.setColor(Color.WHITE);
        }
        
        tv.setLayoutParams(params);
        tv.setBackground(shape);
        
        chatListLayout.addView(tv);

        // ऑटोमैटिक स्क्रॉल करके सबसे नीचे नया मैसेज दिखाना
        chatScrollView.post(() -> chatScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void initZegoCloudCalling() {
        // ZEGOCLOUD SETUP (वॉइस और वीडियो कॉल के लिए)
        // ध्यान दें: आपको ZegoCloud कंसोल से AppID और AppSign लेना होगा।
        long appID = 123456789L;  // अपनी ZegoCloud AppID यहाँ डालें
        String appSign = "YOUR_APP_SIGN_HERE"; // अपना ZegoCloud AppSign यहाँ डालें
        
        try {
            Application application = getApplication();
            ZegoUIKitPrebuiltCallInvitationConfig callInvitationConfig = new ZegoUIKitPrebuiltCallInvitationConfig();
            
            ZegoUIKitPrebuiltCallInvitationService.init(application, appID, appSign, currentUserDocId, currentUserName, callInvitationConfig);

            // कॉल बटन को टारगेट यूज़र (जिसे कॉल करनी है) के साथ जोड़ना
            ZegoSendCallInvitationButton btnVoiceCall = findViewById(R.id.btnVoiceCall);
            ZegoSendCallInvitationButton btnVideoCall = findViewById(R.id.btnVideoCall);

            // वॉइस कॉल बटन
            btnVoiceCall.setIsVideoCall(false);
            btnVoiceCall.setResourceID("zego_uikit_call"); 
            btnVoiceCall.setInvitees(Collections.singletonList(new ZegoUIKitUser(targetUserId, "Web User")));

            // वीडियो कॉल बटन
            btnVideoCall.setIsVideoCall(true);
            btnVideoCall.setResourceID("zego_uikit_call"); 
            btnVideoCall.setInvitees(Collections.singletonList(new ZegoUIKitUser(targetUserId, "Web User")));
            
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // ऐप बंद होने पर कॉलिंग सर्विस बंद करें
        ZegoUIKitPrebuiltCallInvitationService.unInit();
    }
}
