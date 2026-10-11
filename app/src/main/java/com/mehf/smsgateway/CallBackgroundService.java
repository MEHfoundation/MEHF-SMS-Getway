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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            String currentUserDocId = user.getEmail() != null ? user.getEmail().split("@")[0] : user.getUid();
            
            if (callListener != null) callListener.remove(); // Purana listener hatao
            
            callListener = FirebaseFirestore.getInstance().collection("calls").document(currentUserDocId)
                .addSnapshotListener((snapshot, e) -> {
                    if (snapshot != null && snapshot.exists()) {
                        String type = snapshot.getString("type");
                        if ("jitsi_offer".equals(type)) {
                            // 🔥 Call aate hi Nayi Screen (IncomingCallActivity) open karo
                            Intent callIntent = new Intent(this, IncomingCallActivity.class);
                            callIntent.putExtra("callerId", snapshot.getString("callerId"));
                            callIntent.putExtra("roomName", snapshot.getString("roomName"));
                            callIntent.putExtra("isVideo", snapshot.getBoolean("isVideo"));
                            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(callIntent);
                        }
                    }
                });
        }
        return START_STICKY; // Background me hamesha zinda rahega
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (callListener != null) callListener.remove();
    }
}
