package com.mehf.smsgateway;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

public class CallBackgroundService extends Service {
    private ListenerRegistration callListener;
    private boolean isCallWindowOpen = false; // 🔥 Yeh flag baar-baar screen open hone se rookega

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String currentUserDocId = user.getEmail() != null ? user.getEmail().split("@")[0] : user.getUid();
            
            if (callListener != null) callListener.remove();
            
            callListener = FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId)
                .addSnapshotListener((snapshot, e) -> {
                    if (snapshot != null && snapshot.exists()) {
                        String type = snapshot.getString("type");
                        
                        // 🔥 Agar call aayi hai aur pehle se window nahi khuli hai tabhi chalo
                        if ("jitsi_offer".equals(type) && !isCallWindowOpen) {
                            isCallWindowOpen = true;
                            
                            // Turant type update karein taaki listener dobara trigger na ho
                            FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId)
                                .update("type", "ringing-shown");

                            Intent callIntent = new Intent(this, IncomingCallActivity.class);
                            callIntent.putExtra("callerId", snapshot.getString("callerId"));
                            callIntent.putExtra("roomName", snapshot.getString("roomName"));
                            callIntent.putExtra("isVideo", snapshot.getBoolean("isVideo"));
                            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(callIntent);
                        }
                    } else {
                        // Jab call cut ho jaye ya delete ho jaye toh flag ko reset kar dein
                        isCallWindowOpen = false;
                    }
                });
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (callListener != null) callListener.remove();
    }
}
