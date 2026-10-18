package com.gaz.tvaudio;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Runs on the phone. Finds the TV (or uses the typed address), receives the PCM
 * stream and plays it to whatever output is active — your USB-C earphones.
 */
public class ReceiverService extends Service {
    static final String EXTRA_HOST = "host";
    private static final int NOTIF_ID = 2;

    // Keep at most this much audio waiting; anything older is skipped so the
    // sound stays close to the picture.
    private static final int MAX_BACKLOG = Net.BYTES_PER_SEC * 12 / 100;   // 120 ms

    static volatile boolean running;
    static volatile String status = "Not listening";

    private volatile Socket socket;
    private volatile DatagramSocket discovery;
    private AudioTrack track;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private String fixedHost;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || Notifs.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        startForeground(NOTIF_ID,
                Notifs.build(this, ReceiverService.class, "Playing TV sound"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);

        String h = intent.getStringExtra(EXTRA_HOST);
        fixedHost = (h == null || h.trim().isEmpty()) ? null : h.trim();

        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "TvAudio:wifi");
        wifiLock.acquire();
        multicastLock = wm.createMulticastLock("TvAudio:discovery");
        multicastLock.acquire();
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvAudio:receiver");
        wakeLock.acquire();

        track = buildTrack();
        running = true;
        new Thread(this::loop, "receiver").start();
        return START_NOT_STICKY;
    }

    private static AudioTrack buildTrack() {
        int min = AudioTrack.getMinBufferSize(Net.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Net.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, Net.CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
    }

    private void loop() {
        while (running) {
            String host = fixedHost != null ? fixedHost : discover();
            if (!running) break;
            if (host == null) continue;
            try {
                play(host);
            } catch (Exception e) {
                if (running) status = "Lost connection to " + host + " — retrying…";
            }
            closeSocket();
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (Exception ignored) {
                }
            }
            sleep(1500);
        }
    }

    /** Waits for the TV's broadcast and returns its address. */
    private String discover() {
        status = "Looking for the TV on WiFi…";
        try (DatagramSocket ds = new DatagramSocket(null)) {
            discovery = ds;
            ds.setReuseAddress(true);
            ds.setBroadcast(true);
            ds.bind(new InetSocketAddress(Net.DISCOVERY_PORT));
            ds.setSoTimeout(1000);
            byte[] buf = new byte[256];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    ds.receive(p);
                } catch (SocketTimeoutException t) {
                    continue;
                }
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (msg.startsWith(Net.BEACON)) return p.getAddress().getHostAddress();
            }
        } catch (IOException e) {
            if (running) {
                status = "Can't search automatically — type the TV address";
                sleep(2000);
            }
        } finally {
            discovery = null;
        }
        return null;
    }

    private void play(String host) throws IOException {
        status = "Connecting to " + host + "…";
        Socket s = new Socket();
        socket = s;
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(Net.BYTES_PER_SEC / 2);
        s.connect(new InetSocketAddress(host, Net.AUDIO_PORT), 4000);
        s.setSoTimeout(5000);
        DataInputStream in = new DataInputStream(s.getInputStream());

        status = "Playing sound from " + host;
        track.play();
        byte[] buf = new byte[Net.CHUNK_BYTES];
        while (running) {
            in.readFully(buf);
            int backlog = in.available();
            if (backlog > MAX_BACKLOG) {
                int skip = backlog - Net.CHUNK_BYTES * 3;
                skip -= skip % Net.FRAME_BYTES;
                in.skipBytes(skip);
            }
            track.write(buf, 0, buf.length);
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSocket();
        DatagramSocket d = discovery;
        if (d != null) d.close();
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        status = "Not listening";
        super.onDestroy();
    }
}
