package tomato.gui.dps;

import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.DpsData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import util.PropertiesManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class DpsGUI extends JPanel {

    private static final String DISABLE_FILTER = "Default";
    private static DpsGUI INSTANCE;

    private static final String AUTO_SAVE_IMAGE_PROPERTY = "dpsAutoSaveImage";
    private static final File IMAGE_DIR = new File("logs/dps/images");

    private static final int FINISHED_PREVIEW_MS = 10_000;
    private static final int PREVIEW_TICK_MS = 50;

    private TomatoData data;
    private JButton next, prev, live, dList;
    private JToggleButton saveImage;
    private JCheckBox alwaysSaveImage;
    private StringDpsGUI displayString;
    private IconDpsGUI displayIcon;
    private IconDpsGUI imageRenderer;
    private DisplayDpsGUI centerDisplay;
    private JPanel dpsTopPanel;
    private JPanel center;
    private JProgressBar finishedPreviewBar;
    private javax.swing.Timer previewTimer;
    private boolean previewingFinished = false;
    private boolean liveUpdates = true;
    private int index = 0;
    private JComboBox<String> filterComboBox;
    private HashMap<String, String> filterList = new HashMap<>();

    public DpsGUI(TomatoData data) {
        INSTANCE = this;

        this.data = data;

        next = new JButton(">");
        prev = new JButton("<");
        live = new JButton(">>>");
        dList = new JButton("Live");
        saveImage = new JToggleButton("Save Image");
        saveImage.setToolTipText("Saves this dungeon's dps image once it ends");
        alwaysSaveImage = new JCheckBox("Always");
        alwaysSaveImage.setToolTipText("Save an image of every finished dungeon");
        alwaysSaveImage.setSelected("T".equals(PropertiesManager.getProperty(AUTO_SAVE_IMAGE_PROPERTY)));
        alwaysSaveImage.addActionListener(event ->
                PropertiesManager.setProperties(AUTO_SAVE_IMAGE_PROPERTY, alwaysSaveImage.isSelected() ? "T" : "F"));

        next.addActionListener(event -> nextDpsLogDungeon());
        prev.addActionListener(event -> previousDpsLogDungeon());
        live.addActionListener(event -> setLive());
        dList.addActionListener(event -> dListButton(dList));

//        textFilter = new JTextField();
//        textFilter.addKeyListener(new KeyAdapter() {
//            public void keyReleased(KeyEvent e) {
//                String text = textFilter.getText();
//                PropertiesManager.setProperties("nameFilter", text);
//                DpsDisplayOptions.filteredStrings = text.split(" ");
//                updateGui();
//            }
//        });
//        textFilterToggle = new JCheckBox();
//        textFilterToggle.setSelected(true);
//        textFilterToggle.addActionListener(event -> {
//            boolean selected = textFilterToggle.isSelected();
//            textFilter.setEnabled(selected);
//            PropertiesManager.setProperties("toggleFilter", selected ? "T" : "F");
//            DpsDisplayOptions.nameFilter = selected;
//            updateGui();
//        });
        JButton addFilter = new JButton("+");
        addFilter.addActionListener(e -> openFilter());
        filterComboBox = new JComboBox<>(new String[]{DISABLE_FILTER});
        filterComboBox.setPreferredSize(new Dimension(10000, 0));
        filterComboBox.addActionListener(this::comboAction);

        dpsTopPanel = new JPanel();
        dpsTopPanel.setLayout(new BoxLayout(dpsTopPanel, BoxLayout.X_AXIS));
        dpsTopPanel.add(Box.createHorizontalGlue());
        dpsTopPanel.add(addFilter);
        dpsTopPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        dpsTopPanel.add(filterComboBox);
        dpsTopPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        dpsTopPanel.add(prev);
        dpsTopPanel.add(dList);
        dpsTopPanel.add(next);
        dpsTopPanel.add(live);
        dpsTopPanel.add(Box.createRigidArea(new Dimension(10, 0)));
        dpsTopPanel.add(saveImage);
        dpsTopPanel.add(alwaysSaveImage);
        dpsTopPanel.add(Box.createHorizontalGlue());

        finishedPreviewBar = new JProgressBar(0, FINISHED_PREVIEW_MS);
        finishedPreviewBar.setValue(0);
        finishedPreviewBar.setStringPainted(false);
        finishedPreviewBar.setForeground(new Color(21, 220, 166));
        finishedPreviewBar.setPreferredSize(new Dimension(10, 5));
        finishedPreviewBar.setVisible(false);

        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(finishedPreviewBar, BorderLayout.NORTH);
        topContainer.add(dpsTopPanel, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(topContainer, BorderLayout.NORTH);

        center = new JPanel();
        center.setLayout(new BorderLayout());
        add(center, BorderLayout.CENTER);

        displayString = new StringDpsGUI(data);
        displayIcon = new IconDpsGUI(data);
        imageRenderer = new IconDpsGUI(data);
        centerDisplay = displayIcon;
        setCenterDisplay();
    }

    private void dListButton(JButton dpsLabel) {
        DungeonListGUI.open(this, data);
    }

    private void comboAction(ActionEvent actionEvent) {
        JComboBox<String> combo = (JComboBox<String>) actionEvent.getSource();
        String selectedItem = String.valueOf(combo.getSelectedItem());
        setupFilter(selectedItem);
        PropertiesManager.setProperties("filterName", selectedItem);
        update();
    }

    private static void setupFilter(String selectedItem) {
        if (selectedItem.equals(DISABLE_FILTER)) {
            Filter.disable();
            return;
        }
        String s = INSTANCE.filterList.get(selectedItem);
        Filter.selectFilter(s);
    }

    public static String systemTimeToString(long time) {
        if (time == 0) return " [-]";
        long ms = time % 1000;
        if (time < 1000) return String.format(" [%dms]", ms);
        long s = time / 1000 % 60;
        if (time < 60000) return String.format(" [%ds %dms]", s, ms);
        long m = time / 60000 % 60;
        if (time < 3600000) return String.format(" [%dm %ds %dms]", m, s, ms);
        long h = time / 3600000;
        return String.format(" [%dh %dm %ds %dms]", h, m, s, ms);
    }

    private void openFilter() {
        FilterGUI.open(this);
    }

    private void setCenterDisplay() {
        DisplayDpsGUI display;
        boolean actingLive = liveUpdates && !previewingFinished;
        if (actingLive || DpsDisplayOptions.equipmentOption < 3) {
            display = displayString;
        } else if (DpsDisplayOptions.equipmentOption == 3) {
            display = displayIcon;
        } else {
            return;
        }

        if (display.getClass() == centerDisplay.getClass()) return;

        centerDisplay = display;
        center.removeAll();
        center.add(display);
        repaint();
    }

    public static void updateNewTickPacket(TomatoData data) {
        if (!INSTANCE.liveUpdates || INSTANCE.previewingFinished) return;
        INSTANCE.renderData(data.map, data.getEntityHitList(), data.getDeathNotifications(), data.dungeonTime(), true);
    }

    private void renderData(MapInfoPacket map, Entity[] entityHitList, ArrayList<NotificationPacket> notifications, long totalDungeonPcTime, boolean b) {
        SwingUtilities.invokeLater(() -> {
            setCenterDisplay();
            List<Entity> sortedEntityHitList = getSortedEntityList(entityHitList);
            centerDisplay.renderData(map, sortedEntityHitList, notifications, totalDungeonPcTime, b);
        });
    }

    private List<Entity> getSortedEntityList(Entity[] entityHitList) {
        if (DpsDisplayOptions.sortOption == 1) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getFirstDamageTaken).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 2) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::maxHp).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 3) {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getFightTimer).reversed()).collect(Collectors.toList());
        } else if (DpsDisplayOptions.sortOption == 4) {
            return Arrays.stream(entityHitList).filter(Entity::isBossMob).sorted(Comparator.comparingLong(Entity::maxHp).reversed()).collect(Collectors.toList());
        } else {
            return Arrays.stream(entityHitList).sorted(Comparator.comparingLong(Entity::getLastDamageTaken).reversed()).collect(Collectors.toList());
        }
    }

    public static void editFont(Font font) {
        INSTANCE.displayString.editFont(font);
        INSTANCE.displayIcon.editFont(font);
        update();
    }

    public String getFilterString(String a) {
        return filterList.get(a);
    }

    public String[] getComboBoxStrings() {
        return filterList.keySet().toArray(new String[0]);
    }

    public boolean addComboBox(String a, String b) {
        boolean add = false;
        if (!filterList.containsKey(a)) {
            filterComboBox.addItem(a);
            add = true;
        }
        filterList.put(a, b);
        saveFilterProperty();
        return add;
    }

    public void removeComboBox(String o) {
        filterComboBox.removeItem(o);
        filterList.remove(o);
        saveFilterProperty();
    }

    /**
     * Clear the DPS logs.
     */
    public static void clearDpsLogs() {
        INSTANCE.cancelFinishedPreview();
        INSTANCE.data.dpsData.clear();
        INSTANCE.liveUpdates = true;
        INSTANCE.dList.setText("Live");
        update();
    }

    /**
     * Next dungeon displayed by dps calculator.
     */
    public static void nextDpsLogDungeon() {
        INSTANCE.scrollData(1);
    }

    /**
     * Previous dungeon displayed by dps calculator.
     */
    public static void previousDpsLogDungeon() {
        INSTANCE.scrollData(-1);
    }

    private void setLive() {
        INSTANCE.scrollData(1000000000);
    }

    /**
     * Stays in live mode but shows the dps of the dungeon that just ended for a few seconds, so
     * the result can be read without leaving live mode. Automatically resumes live updates once
     * the preview bar runs out, unless the user navigates away manually first.
     */
    public static void showFinishedDungeon(int index) {
        INSTANCE.displayString.resetFreeze();
        INSTANCE.startFinishedPreview(index);
    }

    private void startFinishedPreview(int dpsIndex) {
        cancelFinishedPreview();

        DpsData dpsData = data.dpsData.get(dpsIndex);
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        previewingFinished = true;
        setCenterDisplay();
        renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);

        finishedPreviewBar.setValue(FINISHED_PREVIEW_MS);
        finishedPreviewBar.setVisible(true);

        long start = System.currentTimeMillis();
        previewTimer = new javax.swing.Timer(PREVIEW_TICK_MS, event -> {
            int remaining = (int) Math.max(0, FINISHED_PREVIEW_MS - (System.currentTimeMillis() - start));
            finishedPreviewBar.setValue(remaining);
            if (remaining <= 0) {
                endFinishedPreview();
            }
        });
        previewTimer.start();
    }

    /**
     * Ends the finished-dungeon preview and resumes rendering whatever is currently selected
     * (live data, since liveUpdates was never changed during the preview).
     */
    private void endFinishedPreview() {
        if (previewTimer != null) {
            previewTimer.stop();
            previewTimer = null;
        }
        previewingFinished = false;
        finishedPreviewBar.setVisible(false);
        setCenterDisplay();
        updateGui();
    }

    /**
     * Cancels an in-progress finished-dungeon preview, e.g. when the user manually navigates
     * away (prev/next/live/dungeon list) before it times out on its own.
     */
    private void cancelFinishedPreview() {
        if (previewTimer != null) {
            previewTimer.stop();
            previewTimer = null;
        }
        if (previewingFinished) {
            previewingFinished = false;
            finishedPreviewBar.setVisible(false);
        }
    }

    /**
     * Saves an image of the finished dungeon if the user armed the button during it, or asked for
     * every dungeon to be saved.
     */
    public static void maybeSaveImage(DpsData dpsData) {
        if (!INSTANCE.saveImage.isSelected() && !INSTANCE.alwaysSaveImage.isSelected()) return;
        INSTANCE.saveImage.setSelected(false);
        SwingUtilities.invokeLater(() -> INSTANCE.saveDungeonImage(dpsData));
    }

    private void saveDungeonImage(DpsData dpsData) {
        String name = dpsData.map != null ? dpsData.map.name : "Unknown";
        String time = new SimpleDateFormat("yyyy-MM-dd-HH.mm.ss").format(new Date(dpsData.dungeonStartTime));
        File out = new File(IMAGE_DIR, name.replaceAll("[\\\\/:*?\"<>|]", "_") + " " + time + ".png");
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        try {
            imageRenderer.renderToImage(dpsData.map, getSortedEntityList(entityHitList), dpsData.deathNotifications,
                    dpsData.totalDungeonPcTime, out);
        } catch (IOException e) {
            System.err.println("Failed to save dps image: " + e.getMessage());
        }
    }

    /**
     * Saves the preset chosen by the user.
     */
    private void saveFilterProperty() {
        StringBuilder sb = new StringBuilder();
        for (String v : filterList.values()) {
            sb.append(v).append("\n");
        }
        String substring;
        if (sb.length() > 0) {
            substring = sb.substring(0, sb.length() - 1);
        } else {
            substring = "";
        }
        PropertiesManager.setProperties("filters", substring);
    }

    /**
     * Loads the filter preset chosen by the user.
     */
    public static void loadFilterPreset() {
        String f = PropertiesManager.getProperty("filters");
        if (f != null) {
            String[] lines = f.split("\n");
            for (String l : lines) {
                String name = l.split(",")[0];
                INSTANCE.filterList.put(name, l);
                INSTANCE.filterComboBox.addItem(name);
            }
        }

        String nameFilter = PropertiesManager.getProperty("filterName");
        if (nameFilter != null) {
            setupFilter(nameFilter);
            INSTANCE.filterComboBox.setSelectedItem(nameFilter);
        } else {
            Filter.disable();
            INSTANCE.filterComboBox.setSelectedItem(DISABLE_FILTER);
        }
    }

    public static void update() {
        INSTANCE.updateGui();
    }

    private void updateGui() {
        if (previewingFinished) return; // don't clobber the finished-dungeon preview; its own timer resumes live rendering
        if (liveUpdates) {
            renderData(data.map, data.getEntityHitList(), data.getDeathNotifications(), data.dungeonTime(), true);
        } else {
            DpsData dpsData = data.dpsData.get(index);
            Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
            renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
        }
    }

    public int getIndex() {
        if (liveUpdates) return -1;
        return index;
    }

    public void setIndex(int index) {
        cancelFinishedPreview();
        this.index = index;

        if (index == -1) {
            liveUpdates = true;
            dList.setText("Live");
            return;
        } else {
            liveUpdates = false;
            int size = data.dpsData.size();
            dList.setText((index + 1) + "/" + size);
        }

        setCenterDisplay();
        DpsData dpsData = data.dpsData.get(index);
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
    }

    private void scrollData(int a) {
        cancelFinishedPreview();
        int size = data.dpsData.size();
        if (liveUpdates) {
            if (a > 0 || size == 0) {
                return;
            }
            index = size - 1;
            liveUpdates = false;
            setCenterDisplay();
        } else if (index + a >= size) {
            liveUpdates = true;
            dList.setText("Live");
            setCenterDisplay();
            return;
        } else if (index + a < 0) {
            index = 0;
            return;
        } else {
            index += a;
        }
        dList.setText((index + 1) + "/" + size);

        DpsData dpsData = data.dpsData.get(index);
        Entity[] entityHitList = dpsData.hitList.values().toArray(new Entity[0]);
        renderData(dpsData.map, entityHitList, dpsData.deathNotifications, dpsData.totalDungeonPcTime, false);
    }
}