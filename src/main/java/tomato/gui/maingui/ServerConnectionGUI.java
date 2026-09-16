package tomato.gui.maingui;

import com.google.gson.Gson;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import packets.incoming.MapInfoPacket;
import packets.incoming.ip.IpAddress;
import util.PropertiesManager;

/**
 * Tracks the IP/region/geolocation/ping of the current game connection and of the last Nexus
 * connection, shown in a small popup window (same pattern as TomatoBandwidth). The IP changes at
 * the raw TCP level before any decrypted packet arrives, so a new IP is first shown with an
 * "unknown" map context and retroactively labeled once the next MapInfoPacket arrives.
 * <p>
 * Also keeps a sidebar of manually-saved {map, ip} entries, persisted across restarts.
 */
public class ServerConnectionGUI extends JFrame {

    private static final int GAME_PORT = 2050;
    private static final int PING_INTERVAL_MS = 10_000;
    private static final String SAVED_IPS_PROPERTY = "savedServerIps";
    private static final Gson gson = new Gson();
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
    private static final Map<Integer, GeoInfo> geoCache = new HashMap<>();

    private static ServerConnectionGUI instance;
    private static ConnectionView currentView;
    private static ConnectionView nexusView;
    private static JPanel savedListPanel;

    private static final Connection current = new Connection();
    private static final Connection lastNexus = new Connection();
    private static final List<SavedEntry> savedEntries = loadSavedEntries();

    public static void make(JFrame frame) {
        if (instance != null) return;
        instance = new ServerConnectionGUI();
        instance.setTitle("Server Info");
        instance.setSize(560, 380);
        instance.setLocation(
            frame.getX() + frame.getWidth() / 2 - instance.getWidth() / 2,
            frame.getY() + frame.getHeight() / 2 - instance.getHeight() / 2
        );

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.add(buildConnectionPanel("Current Connection", current, true));
        infoPanel.add(Box.createVerticalStrut(10));
        infoPanel.add(buildConnectionPanel("Last Nexus Connection", lastNexus, false));
        infoPanel.add(Box.createVerticalGlue());

        savedListPanel = new JPanel();
        savedListPanel.setLayout(new BoxLayout(savedListPanel, BoxLayout.Y_AXIS));
        JScrollPane savedScroll = new JScrollPane(savedListPanel);
        savedScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        savedScroll.getVerticalScrollBar().setUnitIncrement(16);
        savedScroll.setPreferredSize(new Dimension(200, 0));
        savedScroll.setBorder(BorderFactory.createTitledBorder("Saved IPs"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, infoPanel, savedScroll);
        splitPane.setResizeWeight(0.65);
        instance.add(splitPane);

        instance.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        instance.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                instance = null;
                currentView = null;
                nexusView = null;
                savedListPanel = null;
            }
        });
        instance.setVisible(true);
        refreshSavedList();
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

    private static JPanel buildConnectionPanel(String title, Connection conn, boolean isCurrent) {
        ConnectionView view = new ConnectionView();
        if (isCurrent) currentView = view;
        else nexusView = view;

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        addRow(panel, gbc, 0, "Map:", view.mapValue, null);
        addRow(panel, gbc, 1, "IP:", view.ipValue, e -> copyToClipboard(conn.ip));
        addRow(panel, gbc, 2, "Region:", view.regionValue, null);
        addRow(panel, gbc, 3, "Location:", view.locationValue, null);
        addRow(panel, gbc, 4, "Ping:", view.pingValue, null);

        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> saveConnection(conn));
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 3;
        panel.add(saveButton, gbc);

        return panel;
    }

    private static void addRow(JPanel panel, GridBagConstraints gbc, int row, String labelText,
                                JLabel valueLabel, ActionListener copyAction) {
        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel(labelText), gbc);
        gbc.gridx = 1;
        panel.add(valueLabel, gbc);
        if (copyAction != null) {
            JButton copyButton = new JButton("Copy");
            gbc.gridx = 2;
            panel.add(copyButton, gbc);
            copyButton.addActionListener(copyAction);
        }
    }

    private static void copyToClipboard(String text) {
        if (text == null) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static void saveConnection(Connection conn) {
        if (conn.ip == null) return;
        savedEntries.removeIf(e -> e.ip.equals(conn.ip));
        SavedEntry entry = new SavedEntry();
        entry.mapLabel = conn.mapLabel;
        entry.ip = conn.ip;
        savedEntries.add(0, entry);
        persistSavedEntries();
        refreshSavedList();
    }

    private static void refreshSavedList() {
        if (savedListPanel == null) return;
        savedListPanel.removeAll();
        for (SavedEntry entry : savedEntries) {
            savedListPanel.add(buildSavedRow(entry));
        }
        savedListPanel.revalidate();
        savedListPanel.repaint();
    }

    private static JPanel buildSavedRow(SavedEntry entry) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel label = new JLabel("<html>" + entry.mapLabel + "<br/>" + entry.ip + "</html>");
        row.add(label, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton copyButton = new JButton("Copy");
        copyButton.addActionListener(e -> copyToClipboard(entry.ip));
        JButton deleteButton = new JButton("X");
        deleteButton.addActionListener(e -> {
            savedEntries.remove(entry);
            persistSavedEntries();
            refreshSavedList();
        });
        buttons.add(copyButton);
        buttons.add(deleteButton);
        row.add(buttons, BorderLayout.EAST);

        return row;
    }

    private static List<SavedEntry> loadSavedEntries() {
        String json = PropertiesManager.getProperty(SAVED_IPS_PROPERTY);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            SavedEntry[] arr = gson.fromJson(json, SavedEntry[].class);
            return arr == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(arr));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void persistSavedEntries() {
        PropertiesManager.setProperties(SAVED_IPS_PROPERTY, gson.toJson(savedEntries));
    }

    private static void refreshDisplay() {
        updateView(currentView, current);
        updateView(nexusView, lastNexus);
    }

    private static void updateView(ConnectionView view, Connection conn) {
        if (view == null) return;
        if (conn.ip == null) {
            view.mapValue.setText("-");
            view.ipValue.setText("(not connected yet)");
            view.regionValue.setText("-");
            view.locationValue.setText("-");
            view.pingValue.setText("-");
            return;
        }
        view.mapValue.setText(conn.mapLabel);
        view.ipValue.setText(conn.ip);
        view.regionValue.setText(conn.region == null || conn.region.isEmpty() ? "-" : conn.region);
        view.locationValue.setText(conn.geo != null ? conn.geo.regionName + ", " + conn.geo.country : "looking up...");
        view.pingValue.setText(conn.pingMs < 0 ? "-" : conn.pingMs + " ms");
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
    }

    /**
     * The JLabels bound to one Connection panel (current or last-nexus), updated in place by
     * refreshDisplay() whenever the underlying Connection's fields change.
     */
    private static class ConnectionView {
        JLabel mapValue = new JLabel("-");
        JLabel ipValue = new JLabel("-");
        JLabel regionValue = new JLabel("-");
        JLabel locationValue = new JLabel("-");
        JLabel pingValue = new JLabel("-");
    }

    private static class SavedEntry {
        String mapLabel;
        String ip;
    }
}
