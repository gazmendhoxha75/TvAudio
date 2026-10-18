package com.gaz.tvaudio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

/** Builds the ongoing notification that every foreground service needs. */
final class Notifs {
    static final String CHANNEL = "stream";
    static final String ACTION_STOP = "com.gaz.tvaudio.STOP";

    private Notifs() {}

    static Notification build(Context c, Class<? extends Service> service, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Audio streaming", NotificationManager.IMPORTANCE_LOW));
        }

        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(c, 1,
                new Intent(c, service).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
