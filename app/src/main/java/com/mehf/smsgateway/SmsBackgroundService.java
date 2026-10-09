package com.mehf.smsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;

public class SmsBackgroundService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        // Foreground Service Notification (ताकि ऐप बैकग्राउंड में बंद न हो)
        Notification notification = new NotificationCompat.Builder(this, "MEHF_SERVICE")
                .setContentTitle("MEHF System Active")
                .setContentText("Listening for messages, calls & SMS...")
                .setSmallIcon(R.drawable.logo) // 👈 YAHAN BADLAV KIYA HAI
                .build();
        startForeground(1, notification);
        
        listenForNewChatsAndCalls();
    }

    private void listenForNewChatsAndCalls() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        
        String myId = user.getEmail() != null ? user.getEmail().split("@")[0] : user.getUid();
        
        // 1. Listen for Chats
        FirebaseFirestore.getInstance().collection("chats")
            .whereEqualTo("receiverId", myId)
            .addSnapshotListener((snaps, e) -> {
                if (e != null || snaps == null) return;
                for (DocumentChange dc : snaps.getDocumentChanges()) {
                    if (dc.getType() == DocumentChange.Type.ADDED) {
                        String msg = dc.getDocument().getString("message");
                        String sender = dc.getDocument().getString("senderName");
                        
                        // मेसेज का नोटिफिकेशन दिखाएं
                        showPopUpNotification(sender, msg);
                    }
                }
            });

         // 2. Listen for Calls
         FirebaseFirestore.getInstance().collection("calls")
            .document(myId)
            .addSnapshotListener((snap, e) -> {
                if (e != null || snap == null || !snap.exists()) return;
                if ("offer".equals(snap.getString("type"))) {
                     showPopUpNotification("📞 Incoming Call", snap.getString("callerId") + " is calling you!");
                }
            });
    }

    private void showPopUpNotification(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent intent = new Intent(this, UsersListActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        
        Notification n = new NotificationCompat.Builder(this, "MEHF_SERVICE")
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.logo) // 👈 YAHAN BHI BADLAV KIYA HAI
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
                
        nm.notify((int) System.currentTimeMillis(), n);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("MEHF_SERVICE", "MEHF Background Alerts", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override 
    public IBinder onBind(Intent intent) { return null; }
    
    @Override 
    public int onStartCommand(Intent intent, int flags, int startId) { 
        return START_STICKY; 
    }
}
