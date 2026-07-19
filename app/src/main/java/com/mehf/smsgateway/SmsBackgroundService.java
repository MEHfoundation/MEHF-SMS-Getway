package com.mehf.smsgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class SmsBackgroundService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "sms_gateway_channel";
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "SMS Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }

            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("MEHF SMS Gateway Active")
                    .setContentText("App background me chal raha hai aur SMS bhej raha hai.")
                    .setSmallIcon(android.R.drawable.ic_dialog_email) // Aap yahan apna app icon bhi laga sakte hain
                    .build();

            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY ye ensure karega ki agar Android system is app ko kill 
        // bhi kar de memory kam hone par, toh ye khud wapas chalu ho jayega.
        return START_STICKY; 
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
