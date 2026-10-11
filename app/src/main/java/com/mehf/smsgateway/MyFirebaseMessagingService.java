package com.mehf.smsgateway;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        // अगर मैसेज में डेटा है (कॉल का डेटा)
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            String type = data.get("type");
            
            if ("offer".equals(type)) {
                String callerId = data.get("callerId");
                String isVideo = data.get("isVideo");
                showIncomingCallScreen(callerId, isVideo);
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void showIncomingCallScreen(String callerId, String isVideo) {
        // 1. स्क्रीन बंद होने पर फोन को जगाना (Wake Lock)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = pm.isInteractive();
        if (!isScreenOn) {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "MEHF:CallWakeLock");
            wl.acquire(10000); // 10 सेकंड के लिए स्क्रीन चालू करें
        }

        // 2. ChatActivity को खोलने का Intent बनाना
        Intent fullScreenIntent = new Intent(this, ChatActivity.class);
        fullScreenIntent.putExtra("targetUserId", callerId);
        fullScreenIntent.putExtra("targetUserName", callerId);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 0,
                fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 3. नोटिफिकेशन चैनल बनाना (Android 8.0+)
        String channelId = "Call_Channel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Rings for incoming voice/video calls");
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            channel.setSound(ringtoneUri, audioAttributes);
            
            notificationManager.createNotificationChannel(channel);
        }

        // 4. फुल-स्क्रीन कॉल नोटिफिकेशन दिखाना
        String callText = ("true".equals(isVideo) ? "📹 Video Call from " : "📞 Voice Call from ") + callerId;
        
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.logo) // अपना लोगो सेट करें
                        .setContentTitle("Incoming Call")
                        .setContentText(callText)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setAutoCancel(true)
                        // 🔥 यही वो लाइन है जो बंद फोन की स्क्रीन पर कॉल का पेज लाती है:
                        .setFullScreenIntent(fullScreenPendingIntent, true);

        Notification notification = notificationBuilder.build();
        notification.flags |= Notification.FLAG_INSISTENT; // जब तक उठाये न, घंटी बजती रहे
        
        notificationManager.notify(12345, notification);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // आप चाहें तो इस टोकन को Firestore में यूज़र की प्रोफाइल में सेव कर सकते हैं
    }
}
