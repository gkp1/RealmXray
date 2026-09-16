package tomato.gui.warnings;

import javax.swing.*;
import java.awt.*;
import tomato.Tomato;
import tomato.version.Version;

/**
 * Small always-on-top splash shown immediately on boot and disposed once the main window is up,
 * so there's visible feedback while CheckVersion/AssetExtractor/TomatoData init runs (which can
 * take a while before Tomato's own frame appears).
 */
public class LoadingGUI {

    private static JWindow window;

    public static void show() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) return;

            JPanel panel = new JPanel(new BorderLayout(0, 10));
            panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.DARK_GRAY),
                BorderFactory.createEmptyBorder(24, 36, 24, 36)
            ));

            Image icon = Toolkit.getDefaultToolkit().getImage(Tomato.imagePath);
            JLabel iconLabel = new JLabel(new ImageIcon(icon.getScaledInstance(48, 48, Image.SCALE_SMOOTH)));
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            panel.add(iconLabel, BorderLayout.NORTH);

            JLabel textLabel = new JLabel("Loading Tomato " + Version.VERSION + "...", SwingConstants.CENTER);
            panel.add(textLabel, BorderLayout.CENTER);

            JProgressBar bar = new JProgressBar();
            bar.setIndeterminate(true);
            panel.add(bar, BorderLayout.SOUTH);

            window = new JWindow();
            window.add(panel);
            window.pack();
            window.setLocationRelativeTo(null);
            window.setAlwaysOnTop(true);
            window.setVisible(true);
        });
    }

    public static void hide() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) {
                window.dispose();
                window = null;
            }
        });
    }
}
