package com.mehf.smsgateway;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.HashMap;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    private EditText etMessage;
    private Button btnSend;
    private RecyclerView chatRecyclerView;
    private FirebaseFirestore db;
    
    // Login system hone tak ise testing ke liye rakha gaya hai
    private String currentUserDocId = "AppUser"; 
    private String currentUserName = "App Admin";
    private String targetUserId = "group_all";
    private String targetSchoolId = "NA";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = FirebaseFirestore.getInstance();
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        ImageButton btnVoiceCall = findViewById(R.id.btnVoiceCall);
        ImageButton btnVideoCall = findViewById(R.id.btnVideoCall);

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessageToFirebase(text);
                etMessage.setText("");
            }
        });

        btnVoiceCall.setOnClickListener(v -> {
            // Yahan ZegoCloud Voice call ayega
        });

        btnVideoCall.setOnClickListener(v -> {
            // Yahan ZegoCloud Video call ayega
        });
    }

    private void sendMessageToFirebase(String text) {
        Map<String, Object> chatData = new HashMap<>();
        chatData.put("senderId", currentUserDocId);
        chatData.put("senderName", currentUserName);
        chatData.put("receiverId", targetUserId);
        chatData.put("schoolId", targetSchoolId);
        chatData.put("message", text);
        chatData.put("status", "sent");
        chatData.put("timestamp", System.currentTimeMillis());

        db.collection("chats").add(chatData);
    }
}
