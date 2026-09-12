package tomato.gui.maingui;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import packets.incoming.MapInfoPacket;
import packets.incoming.ip.IpAddress;

/**
 * Tracks the IP/region/geolocation/ping of the current game connection and of the last Nexus
 * connection, shown in a small popup window (same pattern as TomatoBandwidth). The IP changes at
 * the raw TCP level before any decrypted packet arrives, so a new IP is first shown with an
 * "unknown" map context and retroactively labeled once the next MapInfoPacket arrives.
 */
public class ServerConnectionGUI extends JFrame {

    private static final int GAME_PORT = 2050;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private static final Map<Integer, GeoInfo> geoCache = new HashMap<>();

    private static ServerConnectionGUI instance;
    private static JTextArea infoArea;

    private static final Connection current = new Connection();
    private static final Connection lastNexus = new Connection();

    public static void make(JFrame frame) {
        if (instance != null) return;
        instance = new ServerConnectionGUI();
        instance.setTitle("Server Info");
        instance.setSize(350, 300);
        instance.setLocation(
            frame.getX() + frame.getWidth() / 2 - instance.getWidth() / 2,
            frame.getY() + frame.getHeight() / 2 - instance.getHeight() / 2
        );

        infoArea = new JTextArea();
        infoArea.setEnabled(false);
        infoArea.setEditable(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(infoArea);
        instance.add(scroll);

        instance.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        instance.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                instance = null;
                infoArea = null;
            }
        });
        instance.setVisible(true);
        refreshDisplay();
    }

    /**
     * Called from the packet-capture thread whenever the connection's source IP changes.
     */
    public static void onIpAddress(IpAddress ip) {
        String ipString = String.format(
            "%d.%d.%d.%d",
            Byte.toUnsignedInt(ip.srcAddress[0]),
            Byte.toUnsignedInt(ip.srcAddress[1]),
            Byte.toUnsignedInt(ip.srcAddress[2]),
            Byte.toUnsignedInt(ip.srcAddress[3])
        );

        new Thread(() -> {
            GeoInfo geo = lookupGeo(ip.srcAddressAsInt, ipString);
            long pingMs = measurePing(ipString);
            SwingUtilities.invokeLater(() -> {
                current.ip = ipString;
                current.region = ip.ipAddressName;
                current.geo = geo;
                current.pingMs = pingMs;
                current.mapLabel = "connecting...";
                refreshDisplay();
            });
        }, "ServerConnectionGUI-lookup").start();
    }

    /**
     * Called from the packet-capture thread whenever a new map/dungeon/Nexus is entered.
     * Retroactively labels whatever IP is currently tracked.
     */
    public static void onMapInfo(MapInfoPacket map) {
        String label = map.displayName != null && !map.displayName.isEmpty() ? map.displayName : map.name;
        boolean isNexus = "{s.nexus}".equals(map.displayName);
        SwingUtilities.invokeLater(() -> {
            current.mapLabel = label;
            if (isNexus) {
                lastNexus.copyFrom(current);
                startPingRefresh(lastNexus);
            }
            startPingRefresh(current);
            refreshDisplay();
        });
    }

    private static javax.swing.Timer currentPingTimer;
    private static javax.swing.Timer nexusPingTimer;

    private static void startPingRefresh(Connection target) {
        javax.swing.Timer existing = target == lastNexus ? nexusPingTimer : currentPingTimer;
        if (existing != null) existing.stop();

        String ip = target.ip;
        if (ip == null) return;

        javax.swing.Timer timer = new javax.swing.Timer(PING_INTERVAL_MS, e -> {
            new Thread(() -> {
                long pingMs = measurePing(ip);
                SwingUtilities.invokeLater(() -> {
                    if (ip.equals(target.ip)) {
                        target.pingMs = pingMs;
                        refreshDisplay();
                    }
                });
            }, "ServerConnectionGUI-ping").start();
        });
        timer.start();

        if (target == lastNexus) nexusPingTimer = timer;
        else currentPingTimer = timer;
    }

    private static void refreshDisplay() {
        if (infoArea == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Current Connection\n");
        current.appendTo(sb);
        sb.append("\nLast Nexus Connection\n");
        lastNexus.appendTo(sb);
        infoArea.setText(sb.toString());
    }

    /**
     * TCP connect-time to the game port, more representative of actual connection quality here
     * than ICMP - avoids needing raw sockets/admin rights or shelling out to a native ping.
     */
    private static long measurePing(String ip) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, GAME_PORT), 2000);
            return (System.nanoTime() - start) / 1_000_000;
        } catch (IOException e) {
            return -1;
        }
    }

    private static GeoInfo lookupGeo(int ipInt, String ipString) {
        GeoInfo cached = geoCache.get(ipInt);
        if (cached != null) return cached;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://ip-api.com/json/" + ipString + "?fields=status,country,regionName"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            GeoInfo geo = gson.fromJson(response.body(), GeoInfo.class);
            if (geo != null && "success".equals(geo.status)) {
                geoCache.put(ipInt, geo);
                return geo;
            }
        } catch (Exception ignored) {
            // network hiccup / API unavailable - fall through to unknown
        }
        return null;
    }

    private static class GeoInfo {
        String status;
        String country;
        String regionName;
    }

    private static class Connection {
        String ip;
        String region;
        String mapLabel = "-";
        GeoInfo geo;
        long pingMs = -1;

        void copyFrom(Connection other) {
            ip = other.ip;
            region = other.region;
            mapLabel = other.mapLabel;
            geo = other.geo;
            pingMs = other.pingMs;
        }

        void appendTo(StringBuilder sb) {
            if (ip == null) {
                sb.append("  (not connected yet)\n");
                return;
            }
            sb.append("  Map: ").append(mapLabel).append("\n");
            sb.append("  IP: ").append(ip).append("\n");
            sb.append("  Region: ").append(region == null || region.isEmpty() ? "-" : region).append("\n");
            if (geo != null) {
                sb.append("  Location: ").append(geo.regionName).append(", ").append(geo.country).append("\n");
            } else {
                sb.append("  Location: looking up...\n");
            }
            sb.append("  Ping: ").append(pingMs < 0 ? "-" : pingMs + " ms").append("\n");
        }
    }
}
