package local.zbselective;

import javax.swing.*;
import java.awt.*;

/** Separate JVM: game launchers set java.awt.headless=true. */
public final class RestartDialog {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeAndWait(() -> {
            JOptionPane pane = new JOptionPane(args[0], JOptionPane.INFORMATION_MESSAGE);
            JDialog dialog = pane.createDialog("ZombieBuddy — 必须重启游戏 / Restart required");
            dialog.setAlwaysOnTop(true);
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            dialog.setVisible(true);
            dialog.dispose();
        });
    }
}
