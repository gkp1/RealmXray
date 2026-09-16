package tomato.gui.packetlog;

import packets.packetcapture.logger.FullPacketLogger;
import packets.packetcapture.logger.PacketLogEntry;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Packet Log" tab: shows every frame reaching {@link packets.packetcapture.PacketProcessor}
 * (known and unknown packet types alike), fed by {@link FullPacketLogger}. Complements the
 * per-type-only registration used by the rest of Tomato — this tab shows the raw pipeline.
 */
public class PacketLogGUI extends JPanel {

    private static final String[] COLUMN_NAMES = {
        "Time", "Dir", "Type Id", "Type Name", "Size", "Parsed"
    };
    private static final int TYPE_NAME_COLUMN = 3;
    private static final int MAX_VISIBLE_ROWS = 1000;
    private static final int POLL_INTERVAL_MS = 500;
    private static final int SCROLL_BOTTOM_SLACK_PX = 6;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private final Map<String, Color> typeColorCache = new HashMap<>();

    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JScrollPane tableScrollPane;
    private final JTextArea detailArea;
    private final JTextField filterField;
    private final JTextField excludeField;
    private final JComboBox<String> directionFilter;
    private final JCheckBox errorsOnlyCheckbox;
    private final JCheckBox pauseCheckbox;
    private final JLabel countLabel;
    private final JLabel fileLabel;

    // Entries currently shown in the table, in model-row order (i.e. index == model row index,
    // not view row index — use table.convertRowIndexToModel() when reading from a view row).
    private List<PacketLogEntry> visibleEntries = new ArrayList<>();
    private Timer pollTimer;

    public PacketLogGUI() {
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFillsViewportHeight(true);
        int[] widths = {90, 40, 60, 220, 60, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        TypeColorRenderer coloredRenderer = new TypeColorRenderer();
        table.setDefaultRenderer(Object.class, coloredRenderer);
        table.setDefaultRenderer(String.class, coloredRenderer);
        table.setDefaultRenderer(Integer.class, coloredRenderer);
        table.setDefaultRenderer(Number.class, coloredRenderer);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetailForSelection();
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowContextMenu(e);
            }
        });

        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);

        filterField = new JTextField(14);
        excludeField = new JTextField(14);
        directionFilter = new JComboBox<>(new String[]{"All", "Incoming", "Outgoing"});
        errorsOnlyCheckbox = new JCheckBox("Only unparsed/unknown");
        pauseCheckbox = new JCheckBox("Pause");
        countLabel = new JLabel("0 entries");
        fileLabel = new JLabel("Auto-logging: starting...");

        add(buildTopPanel(), BorderLayout.NORTH);
        tableScrollPane = new JScrollPane(table);
        tableScrollPane.getVerticalScrollBar().setUnitIncrement(24);
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            tableScrollPane,
            new JScrollPane(detailArea)
        );
        splitPane.setResizeWeight(0.65);
        add(splitPane, BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        startPolling();
    }

    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.add(new JLabel("Type filter:"));
        panel.add(filterField);
        panel.add(new JLabel("Exclude:"));
        panel.add(excludeField);
        JButton clearExcludeButton = new JButton("Clear excludes");
        clearExcludeButton.addActionListener(e -> {
            excludeField.setText("");
            refreshTable();
        });
        panel.add(clearExcludeButton);
        panel.add(new JLabel("Direction:"));
        panel.add(directionFilter);
        panel.add(errorsOnlyCheckbox);
        panel.add(pauseCheckbox);

        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> {
            FullPacketLogger.INSTANCE.clear();
            refreshTable();
        });
        panel.add(clearButton);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> saveLog());
        left.add(saveButton);
        left.add(fileLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(countLabel, BorderLayout.EAST);
        return panel;
    }

    /**
     * Packets are always being written to the current automatic log (rx-logs/automatic) in the
     * background; this just copies that file into rx-logs/saved so it survives the automatic
     * log's rolling retention.
     */
    private void saveLog() {
        try {
            File saved = FullPacketLogger.INSTANCE.saveCurrentLog();
            if (saved == null) {
                JOptionPane.showMessageDialog(this, "No automatic log to save yet.",
                    "Packet Log", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JOptionPane.showMessageDialog(this, "Saved to: " + saved.getAbsolutePath(),
                "Packet Log", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Failed to save log: " + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateFileLabel() {
        File current = FullPacketLogger.INSTANCE.getCurrentLogFile();
        fileLabel.setText(current == null
            ? "Auto-logging: starting..."
            : "Auto-logging to: " + current.getAbsolutePath());
    }

    private void startPolling() {
        pollTimer = new Timer(POLL_INTERVAL_MS, e -> refreshTable());
        pollTimer.start();
    }

    /**
     * Stops the background polling timer. Call when the tab/frame is disposed.
     */
    public void stopPolling() {
        if (pollTimer != null) pollTimer.stop();
    }

    private void refreshTable() {
        updateFileLabel();
        if (pauseCheckbox.isSelected()) return;

        List<PacketLogEntry> entries = FullPacketLogger.INSTANCE.getRecent(MAX_VISIBLE_ROWS);

        String typeFilter = filterField.getText().trim().toLowerCase();
        List<String> excludeTerms = parseExcludeTerms();
        String direction = (String) directionFilter.getSelectedItem();
        boolean errorsOnly = errorsOnlyCheckbox.isSelected();

        // Remember what's selected (by identity, survives re-sorting/filtering) and whether the
        // viewport was pinned to the bottom, so a live-refresh doesn't yank the user's scroll
        // position or selection out from under them.
        PacketLogEntry previouslySelectedEntry = null;
        int viewRow = table.getSelectedRow();
        if (viewRow >= 0) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < visibleEntries.size()) {
                previouslySelectedEntry = visibleEntries.get(modelRow);
            }
        }
        JScrollBar vbar = tableScrollPane.getVerticalScrollBar();
        boolean wasAtBottom = !vbar.isVisible()
            || vbar.getValue() + vbar.getVisibleAmount() >= vbar.getMaximum() - SCROLL_BOTTOM_SLACK_PX;
        int savedScrollValue = vbar.getValue();

        List<PacketLogEntry> newVisibleEntries = new ArrayList<>();
        tableModel.setRowCount(0);

        int total = FullPacketLogger.INSTANCE.size();
        countLabel.setText(total + " entries in buffer (showing up to " + MAX_VISIBLE_ROWS + ")");

        for (PacketLogEntry entry : entries) {
            if (!typeFilter.isEmpty() && !entry.typeName.toLowerCase().contains(typeFilter)) continue;
            if (isExcluded(entry, excludeTerms)) continue;
            if ("Incoming".equals(direction) && !entry.incoming) continue;
            if ("Outgoing".equals(direction) && entry.incoming) continue;
            if (errorsOnly && entry.deserialized) continue;

            newVisibleEntries.add(entry);
            tableModel.addRow(new Object[]{
                timeFormat.format(new Date(entry.timestamp)),
                entry.incoming ? "IN" : "OUT",
                entry.typeId,
                entry.typeName,
                entry.size,
                entry.deserialized ? "Yes" : "No"
            });
        }
        visibleEntries = newVisibleEntries;

        if (previouslySelectedEntry != null) {
            int newModelRow = visibleEntries.indexOf(previouslySelectedEntry);
            if (newModelRow >= 0) {
                int newViewRow = table.convertRowIndexToView(newModelRow);
                if (newViewRow >= 0) table.setRowSelectionInterval(newViewRow, newViewRow);
            }
        }

        SwingUtilities.invokeLater(() -> {
            if (wasAtBottom) {
                vbar.setValue(vbar.getMaximum());
            } else {
                vbar.setValue(Math.min(savedScrollValue, vbar.getMaximum()));
            }
        });
    }

    private void showDetailForSelection() {
        int viewRow = table.getSelectedRow();
        PacketLogEntry entry = entryForViewRow(viewRow);
        if (entry == null) {
            detailArea.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Direction: ").append(entry.incoming ? "Incoming" : "Outgoing").append("\n");
        sb.append("Type: ").append(entry.typeName).append(" (").append(entry.typeId).append(")\n");
        sb.append("Size: ").append(entry.size).append(" bytes\n");
        sb.append("Parsed: ").append(entry.deserialized).append("\n\n");
        String json = entry.getJson();
        if (entry.deserialized && !json.isEmpty()) {
            sb.append(json);
        } else if (entry.raw != null) {
            sb.append("Raw bytes: ").append(Arrays.toString(entry.raw));
        }
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private PacketLogEntry entryForViewRow(int viewRow) {
        if (viewRow < 0) return null;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= visibleEntries.size()) return null;
        return visibleEntries.get(modelRow);
    }

    /**
     * Comma-separated substrings from the Exclude field, lowercased and trimmed.
     */
    private List<String> parseExcludeTerms() {
        String raw = excludeField.getText();
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    private boolean isExcluded(PacketLogEntry entry, List<String> excludeTerms) {
        if (excludeTerms.isEmpty()) return false;
        String typeName = entry.typeName.toLowerCase();
        for (String term : excludeTerms) {
            if (typeName.contains(term)) return true;
        }
        return false;
    }

    private void maybeShowContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int viewRow = table.rowAtPoint(e.getPoint());
        if (viewRow < 0) return;
        table.setRowSelectionInterval(viewRow, viewRow);

        PacketLogEntry entry = entryForViewRow(viewRow);
        if (entry == null) return;

        JPopupMenu menu = new JPopupMenu();
        JMenuItem hideItem = new JMenuItem("Hide \"" + entry.typeName + "\"");
        hideItem.addActionListener(a -> addExcludeTerm(entry.typeName));
        menu.add(hideItem);
        menu.show(table, e.getX(), e.getY());
    }

    private void addExcludeTerm(String typeName) {
        Set<String> terms = new LinkedHashSet<>(parseExcludeTerms());
        terms.add(typeName.toLowerCase());
        excludeField.setText(String.join(", ", terms));
        refreshTable();
    }

    /**
     * Deterministic color per packet type name, derived from its hash so the same type always
     * gets the same hue across refreshes/sessions.
     */
    private Color colorForType(String typeName) {
        return typeColorCache.computeIfAbsent(typeName, name -> {
            float hue = (Math.abs(name.hashCode()) % 360) / 360f;
            return Color.getHSBColor(hue, 0.85f, 0.95f);
        });
    }

    private static Color blend(Color base, Color tint, double ratio) {
        int r = (int) (base.getRed() * (1 - ratio) + tint.getRed() * ratio);
        int g = (int) (base.getGreen() * (1 - ratio) + tint.getGreen() * ratio);
        int b = (int) (base.getBlue() * (1 - ratio) + tint.getBlue() * ratio);
        return new Color(r, g, b);
    }

    /**
     * Tints each row's background by its packet type (blended into the theme's own colors so it
     * stays legible in both light and dark look-and-feels), instead of a flat fixed color.
     */
    private class TypeColorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            Component comp = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            Object typeName = tbl.getValueAt(row, TYPE_NAME_COLUMN);
            if (typeName != null) {
                Color base = isSelected ? tbl.getSelectionBackground() : tbl.getBackground();
                Color tint = colorForType(typeName.toString());
                comp.setBackground(blend(base, tint, isSelected ? 0.12 : 0.22));
                comp.setForeground(isSelected ? tbl.getSelectionForeground() : tbl.getForeground());
            }
            return comp;
        }
    }
}
