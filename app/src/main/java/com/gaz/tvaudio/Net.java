package com.gaz.tvaudio;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared constants and small network helpers. */
final class Net {
    static final int AUDIO_PORT = 50505;
    static final int DISCOVERY_PORT = 50506;
    static final String BEACON = "TVAUDIO1";

    // 48 kHz, stereo, 16-bit PCM: about 1.5 Mbit/s, fine for home WiFi.
    static final int SAMPLE_RATE = 48000;
    static final int FRAME_BYTES = 4;                       // 2 channels x 2 bytes
    static final int BYTES_PER_SEC = SAMPLE_RATE * FRAME_BYTES;
    static final int CHUNK_BYTES = BYTES_PER_SEC / 100;     // 10 ms

    private Net() {}

    /** This device's WiFi/LAN IPv4 address, or null. */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Broadcast addresses of all active LAN interfaces, plus the global one. */
    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
            InetAddress all = InetAddress.getByName("255.255.255.255");
            if (!out.contains(all)) out.add(all);
        } catch (Exception ignored) {
        }
        return out;
    }
}
