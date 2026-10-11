package com.mehf.smsgateway;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import org.jitsi.meet.sdk.JitsiMeetActivity;
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions;
import java.net.URL;

public class IncomingCallActivity extends AppCompatActivity {
    private MediaPlayer ringtonePlayer;
    private String callerId, roomName, currentUserDocId;
    private boolean isVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 🔥 WAKE UP SCREEN ON LOCK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        callerId = getIntent().getStringExtra("callerId");
        roomName = getIntent().getStringExtra("roomName");
        isVideo = getIntent().getBooleanExtra("isVideo", true);

        // 🔥 FULL SCREEN CALL UI (No XML Needed)
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#1e3c72")); // Blue background

        TextView tvTitle = new TextView(this);
        tvTitle.setText((isVideo ? "📹 Video Call\n" : "📞 Voice Call\n") + "From: " + callerId);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(24f);
        tvTitle.setGravity(Gravity.CENTER);
        layout.addView(tvTitle);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.CENTER);
        btnLayout.setPadding(0, 150, 0, 0);

        Button btnDecline = new Button(this);
        btnDecline.setText("DECLINE ❌");
        btnDecline.setBackgroundColor(Color.parseColor("#F44336"));
        btnDecline.setTextColor(Color.WHITE);
        btnDecline.setOnClickListener(v -> declineCall());
        btnLayout.addView(btnDecline);

        android.widget.Space space = new android.widget.Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(50, 1));
        btnLayout.addView(space);

        Button btnAccept = new Button(this);
        btnAccept.setText("ACCEPT 📞");
        btnAccept.setBackgroundColor(Color.parseColor("#4CAF50"));
        btnAccept.setTextColor(Color.WHITE);
        btnAccept.setOnClickListener(v -> acceptCall());
        btnLayout.addView(btnAccept);

        layout.addView(btnLayout);
        setContentView(layout);

        startRingtone();

        // Listen for Remote Disconnect (Agar samne wale ne call kaat di)
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserDocId = FirebaseAuth.getInstance().getCurrentUser().getEmail().split("@")[0];
            FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId)
                .addSnapshotListener((snapshot, e) -> {
                    if (snapshot == null || !snapshot.exists()) finishCall();
                });
        }
    }

    private void acceptCall() {
        FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId).delete();
        stopRingtone();
        try {
            org.jitsi.meet.sdk.JitsiMeetConferenceOptions options = new org.jitsi.meet.sdk.JitsiMeetConferenceOptions.Builder()
                    .setServerURL(new java.net.URL("https://meet.ffmuc.net"))
                    .setRoom(roomName)
                    .setAudioMuted(false)
                    .setVideoMuted(!isVideo)
                    .setSubject("MEHF Secure Call")
                    .setFeatureFlag("prejoinpage.enabled", false)
                    .setFeatureFlag("invite.enabled", true)
                    .setFeatureFlag("welcomepage.enabled", false)
                    .setFeatureFlag("tile-view.enabled", true)
                    .build();
            org.jitsi.meet.sdk.JitsiMeetActivity.launch(this, options);
        } catch (Exception e) {
            Toast.makeText(this, "Accept Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    private void declineCall() {
        FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId).delete();
        finishCall();
    }

    private void startRingtone() {
        try {
            ringtonePlayer = MediaPlayer.create(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            ringtonePlayer.setLooping(true);
            ringtonePlayer.start();
        } catch (Exception e) {}
    }

    private void stopRingtone() {
        if (ringtonePlayer != null && ringtonePlayer.isPlaying()) {
            ringtonePlayer.stop();
            ringtonePlayer.release();
        }
    }

    private void finishCall() {
        stopRingtone();
        finish();
    }
}
