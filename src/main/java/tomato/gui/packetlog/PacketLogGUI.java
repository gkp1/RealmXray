package tomato.gui.packetlog;

import packets.packetcapture.logger.FullPacketLogger;
import packets.packetcapture.logger.PacketLogEntry;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * "Packet Log" tab: shows every frame reaching {@link packets.packetcapture.PacketProcessor}
 * (known and unknown packet types alike), fed by {@link FullPacketLogger}. Complements the
 * per-type-only registration used by the rest of Tomato — this tab shows the raw pipeline.
 */
public class PacketLogGUI extends JPanel {

    private static final String[] COLUMN_NAMES = {
        "Time", "Dir", "Type Id", "Type Name", "Size", "Parsed"
    };
    private static final int MAX_VISIBLE_ROWS = 1000;
    private static final int POLL_INTERVAL_MS = 500;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea detailArea;
    private final JTextField filterField;
    private final JComboBox<String> directionFilter;
    private final JCheckBox errorsOnlyCheckbox;
    private final JCheckBox pauseCheckbox;
    private final JLabel countLabel;
    private final JLabel fileLabel;

    private List<PacketLogEntry> lastEntries;
    private Timer pollTimer;
    private File outputFile;

    public PacketLogGUI() {
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {90, 40, 60, 220, 60, 60};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetailForSelection();
        });

        detailArea = new JTextArea();
        detailArea.setEditable(false);
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);

        filterField = new JTextField(14);
        directionFilter = new JComboBox<>(new String[]{"All", "Incoming", "Outgoing"});
        errorsOnlyCheckbox = new JCheckBox("Only unparsed/unknown");
        pauseCheckbox = new JCheckBox("Pause");
        countLabel = new JLabel("0 entries");
        fileLabel = new JLabel("Not saving to file");

        add(buildTopPanel(), BorderLayout.NORTH);
        JSplitPane splitPane = new JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(table),
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
        JButton saveButton = new JButton("Save to file...");
        saveButton.addActionListener(e -> chooseOutputFile());
        JButton stopSavingButton = new JButton("Stop saving");
        stopSavingButton.addActionListener(e -> stopSavingToFile());
        left.add(saveButton);
        left.add(stopSavingButton);
        left.add(fileLabel);

        panel.add(left, BorderLayout.WEST);
        panel.add(countLabel, BorderLayout.EAST);
        return panel;
    }

    private void chooseOutputFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("packet-log.jsonl"));
        int result = chooser.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            try {
                outputFile = chooser.getSelectedFile();
                FullPacketLogger.INSTANCE.setOutputFile(outputFile);
                fileLabel.setText("Saving to: " + outputFile.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(
                    this,
                    "Failed to open file for writing: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        }
    }

    private void stopSavingToFile() {
        try {
            FullPacketLogger.INSTANCE.setOutputFile(null);
            outputFile = null;
            fileLabel.setText("Not saving to file");
        } catch (Exception ignored) {
        }
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
        if (pauseCheckbox.isSelected()) return;

        List<PacketLogEntry> entries = FullPacketLogger.INSTANCE.getRecent(MAX_VISIBLE_ROWS);
        lastEntries = entries;

        String typeFilter = filterField.getText().trim().toLowerCase();
        String direction = (String) directionFilter.getSelectedItem();
        boolean errorsOnly = errorsOnlyCheckbox.isSelected();

        int previouslySelected = table.getSelectedRow();
        tableModel.setRowCount(0);

        int total = FullPacketLogger.INSTANCE.size();
        countLabel.setText(total + " entries in buffer (showing up to " + MAX_VISIBLE_ROWS + ")");

        for (PacketLogEntry entry : entries) {
            if (!typeFilter.isEmpty() && !entry.typeName.toLowerCase().contains(typeFilter)) continue;
            if ("Incoming".equals(direction) && !entry.incoming) continue;
            if ("Outgoing".equals(direction) && entry.incoming) continue;
            if (errorsOnly && entry.deserialized) continue;

            tableModel.addRow(new Object[]{
                timeFormat.format(new Date(entry.timestamp)),
                entry.incoming ? "IN" : "OUT",
                entry.typeId,
                entry.typeName,
                entry.size,
                entry.deserialized ? "Yes" : "No"
            });
        }

        if (previouslySelected >= 0 && previouslySelected < tableModel.getRowCount()) {
            table.setRowSelectionInterval(previouslySelected, previouslySelected);
        }
    }

    private void showDetailForSelection() {
        int row = table.getSelectedRow();
        if (row < 0 || lastEntries == null) {
            detailArea.setText("");
            return;
        }

        // Re-resolve the visible row against the filtered subset used to build the table.
        PacketLogEntry entry = findEntryForVisibleRow(row);
        if (entry == null) {
            detailArea.setText("");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Direction: ").append(entry.incoming ? "Incoming" : "Outgoing").append("\n");
        sb.append("Type: ").append(entry.typeName).append(" (").append(entry.typeId).append(")\n");
        sb.append("Size: ").append(entry.size).append(" bytes\n");
        sb.append("Parsed: ").append(entry.deserialized).append("\n\n");
        if (entry.deserialized && !entry.json.isEmpty()) {
            sb.append(entry.json);
        } else if (entry.raw != null) {
            sb.append("Raw bytes: ").append(java.util.Arrays.toString(entry.raw));
        }
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private PacketLogEntry findEntryForVisibleRow(int visibleRow) {
        if (lastEntries == null) return null;
        String typeFilter = filterField.getText().trim().toLowerCase();
        String direction = (String) directionFilter.getSelectedItem();
        boolean errorsOnly = errorsOnlyCheckbox.isSelected();

        int count = -1;
        for (PacketLogEntry entry : lastEntries) {
            if (!typeFilter.isEmpty() && !entry.typeName.toLowerCase().contains(typeFilter)) continue;
            if ("Incoming".equals(direction) && !entry.incoming) continue;
            if ("Outgoing".equals(direction) && entry.incoming) continue;
            if (errorsOnly && entry.deserialized) continue;
            count++;
            if (count == visibleRow) return entry;
        }
        return null;
    }
}
