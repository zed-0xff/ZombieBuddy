package local.zbselective;

import javax.swing.*;
import java.awt.*;

/** Separate JVM: game launchers set java.awt.headless=true. */
public final class RestartDialog {
    public static void main(String[] args) throws Exception {
        local.zbselective.i18n.UiText.initialize();
        if (args.length != 1 || GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeAndWait(() -> {
            JOptionPane pane = new JOptionPane(args[0], JOptionPane.INFORMATION_MESSAGE);
            JDialog dialog = pane.createDialog(local.zbselective.i18n.UiText.text(
                "ZombieBuddy — Restart required", "ZombieBuddy — 必须重启游戏"));
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setVisible(true);
            dialog.dispose();
        });
    }
}
