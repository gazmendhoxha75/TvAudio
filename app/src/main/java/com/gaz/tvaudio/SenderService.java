package com.gaz.tvaudio;

import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Runs on the TV. Captures what the TV's apps are playing and streams it as raw PCM
 * over TCP to every connected phone. Also announces itself on the LAN with UDP
 * broadcasts so phones can find it without typing an IP address.
 */
public class SenderService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final int NOTIF_ID = 1;

    static volatile boolean running;
    static volatile String status = "Not sending";

    private MediaProjection projection;
    private AudioRecord record;
    private ServerSocket server;
    private DatagramSocket beacon;
    private PowerManager.WakeLock wakeLock;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

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

        // Must be in the foreground with type mediaProjection BEFORE getMediaProjection().
        startForeground(NOTIF_ID,
                Notifs.build(this, SenderService.class, "Sending TV sound over WiFi"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            status = "Capture permission was not granted";
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        try {
            record = buildRecorder(projection);
        } catch (Exception e) {
            status = "This TV refused audio capture: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TvAudio:sender");
        wakeLock.acquire();

        running = true;
        updateStatus();
        new Thread(this::acceptLoop, "accept").start();
        new Thread(this::beaconLoop, "beacon").start();
        new Thread(this::captureLoop, "capture").start();
        return START_NOT_STICKY;
    }

    private static AudioRecord buildRecorder(MediaProjection mp) {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Net.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int min = AudioRecord.getMinBufferSize(Net.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, Net.CHUNK_BYTES * 8);
        AudioRecord r = new AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .setAudioPlaybackCaptureConfig(cfg)
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("recorder did not initialise");
        }
        return r;
    }

    private void captureLoop() {
        byte[] buf = new byte[Net.CHUNK_BYTES];
        try {
            record.startRecording();
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) continue;
                n -= n % Net.FRAME_BYTES;
                if (n == 0 || clients.isEmpty()) continue;
                byte[] chunk = new byte[n];
                System.arraycopy(buf, 0, chunk, 0, n);
                for (Client c : clients) c.offer(chunk);
            }
        } catch (Exception e) {
            if (running) status = "Capture stopped: " + e.getMessage();
        }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(Net.AUDIO_PORT));
            while (running) {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                s.setSendBufferSize(Net.BYTES_PER_SEC / 4);
                Client c = new Client(s);
                clients.add(c);
                c.start();
                updateStatus();
            }
        } catch (IOException e) {
            if (running) status = "Network error: " + e.getMessage();
        }
    }

    private void beaconLoop() {
        String ip = Net.localIp();
        byte[] msg = (Net.BEACON + " " + Build.MODEL).getBytes(StandardCharsets.UTF_8);
        try {
            beacon = new DatagramSocket();
            beacon.setBroadcast(true);
            while (running) {
                for (InetAddress b : Net.broadcastAddresses()) {
                    try {
                        beacon.send(new DatagramPacket(msg, msg.length, b, Net.DISCOVERY_PORT));
                    } catch (IOException ignored) {
                    }
                }
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateStatus() {
        String ip = Net.localIp();
        status = "Sending from " + (ip == null ? "this TV" : ip)
                + " — " + clients.size() + " phone(s) listening";
    }

    @Override
    public void onDestroy() {
        running = false;
        for (Client c : clients) c.close();
        clients.clear();
        closeQuietly(server);
        if (beacon != null) beacon.close();
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            record.release();
            record = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (!status.startsWith("This TV") && !status.startsWith("Capture")) status = "Not sending";
        super.onDestroy();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** One phone. Has its own writer thread so a slow phone never stalls capture. */
    private final class Client extends Thread {
        private final Socket socket;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(30); // ~300 ms

        Client(Socket s) {
            super("client");
            socket = s;
        }

        void offer(byte[] chunk) {
            while (!queue.offer(chunk)) queue.poll();   // drop oldest when behind
        }

        @Override
        public void run() {
            try (OutputStream out = socket.getOutputStream()) {
                while (running && !socket.isClosed()) {
                    byte[] chunk = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk != null) out.write(chunk);
                }
            } catch (Exception ignored) {
            } finally {
                close();
                clients.remove(this);
                if (running) updateStatus();
            }
        }

        void close() {
            closeQuietly(socket);
        }
    }
}
