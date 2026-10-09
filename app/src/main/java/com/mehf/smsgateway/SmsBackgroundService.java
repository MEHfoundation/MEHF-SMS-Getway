package com.mehf.smsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
                .setContentText("Listening for messages & calls...")
                .setSmallIcon(R.drawable.logo)
                .build();
        startForeground(1, notification);
        
        listenForNewChatsAndCalls();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(new Locale("hi", "IN"));
            isTtsReady = true;
        }
    }

    private void speak(String text) {
        if (isTtsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void listenForNewChatsAndCalls() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        
        String myId = user.getEmail() != null ? user.getEmail().split("@")[0] : user.getUid();
        
        // 1. Message Listener (For Delivered Ticks & TTS)
        FirebaseFirestore.getInstance().collection("chats")
            .whereEqualTo("receiverId", myId)
            .addSnapshotListener((snaps, e) -> {
                if (e != null || snaps == null) return;
                for (DocumentChange dc : snaps.getDocumentChanges()) {
                    String docId = dc.getDocument().getId();
                    String status = dc.getDocument().getString("status");
                    
                    // 🔥 TICK LOGIC: Update to 'Delivered' in Background
                    if ("sent".equals(status)) {
                        FirebaseFirestore.getInstance().collection("chats").document(docId).update("status", "delivered");
                    }

                    if (dc.getType() == DocumentChange.Type.ADDED) {
                        Long tsObj = dc.getDocument().getLong("timestamp");
                        long msgTime = tsObj != null ? tsObj : 0;
                        if (System.currentTimeMillis() - msgTime < 15000) {
                            String msg = dc.getDocument().getString("message");
                            String sender = dc.getDocument().getString("senderName");
                            showPopUpNotification(sender, msg);
                            speak("नया मैसेज आया है, " + sender + " से");
                        }
                    }
                }
            });

         // 2. Call Listener (For Waking Screen)
         FirebaseFirestore.getInstance().collection("calls")
            .document(myId)
            .addSnapshotListener((snap, e) -> {
                if (e != null || snap == null || !snap.exists()) return;
                if ("offer".equals(snap.getString("type"))) {
                    String caller = snap.getString("callerId");
                    
                    // 🔥 WAKE SCREEN INTENT
                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra("targetUserId", caller);
                    intent.putExtra("targetUserName", caller);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    
                    PendingIntent pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    
                    NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "MEHF_CALL_CHANNEL")
                            .setContentTitle("📞 Incoming Call")
                            .setContentText(caller + " is calling...")
                            .setSmallIcon(R.drawable.logo)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setCategory(NotificationCompat.CATEGORY_CALL)
                            .setFullScreenIntent(pi, true) 
                            .setAutoCancel(true);
                            
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    nm.notify(100, builder.build());
                    speak("इनकमिंग कॉल आ रही है, " + caller + " से");
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
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        nm.notify((int) System.currentTimeMillis(), n);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel msgChannel = new NotificationChannel("MEHF_SERVICE", "MEHF Messages", NotificationManager.IMPORTANCE_HIGH);
                nm.createNotificationChannel(msgChannel);
                
                // 🔥 SILENT CALL CHANNEL (To prevent double ringing)
                NotificationChannel callChannel = new NotificationChannel("MEHF_CALL_CHANNEL", "Incoming Calls", NotificationManager.IMPORTANCE_HIGH);
                callChannel.setSound(null, null); 
                callChannel.enableVibration(true);
                nm.createNotificationChannel(callChannel);
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (tts != null) tts.shutdown(); super.onDestroy(); }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { stopForeground(true); }
        stopSelf();
    }
}
