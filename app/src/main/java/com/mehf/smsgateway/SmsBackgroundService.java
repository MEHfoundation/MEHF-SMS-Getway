package com.mehf.smsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import androidx.core.app.NotificationCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import java.util.Locale;

public class SmsBackgroundService extends Service implements TextToSpeech.OnInitListener {

    private TextToSpeech tts;
    private boolean isTtsReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        tts = new TextToSpeech(this, this);
        createNotificationChannels();
        
        Notification notification = new NotificationCompat.Builder(this, "MEHF_SERVICE")
                .setContentTitle("MEHF System Active")
                .setContentText("Listening for messages...")
                .setSmallIcon(R.drawable.logo)
                .build();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
        
        listenForNewChats(); // 🔥 Renamed method: Removed manual call listeners
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("hi", "IN"));
            isTtsReady = true;
        }
    }

    private void speak(String text) {
        // 🔥 AUDIO FIX: Ensure phone is NOT stuck in "Call Mode" silently
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(false);
        }
        
        if (isTtsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // 🔥 Removed the manual "calls" collection listener. ZegoCloud handles all incoming calls now.
    private void listenForNewChats() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        
        String myId = user.getEmail() != null ? user.getEmail().split("@")[0] : user.getUid();
        
        FirebaseFirestore.getInstance().collection("chats")
            .whereEqualTo("receiverId", myId)
            .addSnapshotListener((snaps, e) -> {
                if (e != null || snaps == null) return;
                for (DocumentChange dc : snaps.getDocumentChanges()) {
                    String docId = dc.getDocument().getId();
                    String status = dc.getDocument().getString("status");
                    
                    if (dc.getType() == DocumentChange.Type.ADDED) {
                        if ("sent".equals(status)) {
                            FirebaseFirestore.getInstance().collection("chats").document(docId).update("status", "delivered");
                            
                            Long tsObj = dc.getDocument().getLong("timestamp");
                            long msgTime = tsObj != null ? tsObj : 0;
                            // 🔥 Ensure we only alert for recent messages
                            if (System.currentTimeMillis() - msgTime < 15000) {
                                String msg = dc.getDocument().getString("message");
                                String sender = dc.getDocument().getString("senderName");
                                
                                // Do not announce if the message is an automated call log from ZegoCloud
                                if (msg != null && !msg.contains("(Duration:")) {
                                    showPopUpNotification(sender, msg);
                                    speak(sender + " का मैसेज है: " + msg);
                                }
                            }
                        }
                    }
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
                .setSmallIcon(R.drawable.logo)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL) // 🔥 FIX: This will force the system notification sound/ping
                .build();
        nm.notify((int) System.currentTimeMillis(), n);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel msgChannel = new NotificationChannel("MEHF_SERVICE", "MEHF Messages", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(msgChannel);
                // Removed custom call channel creation as Zego handles it natively
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    
    @Override 
    public void onDestroy() { 
        if (tts != null) tts.shutdown(); 
        super.onDestroy(); 
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { stopForeground(true); }
        stopSelf();
    }
}
