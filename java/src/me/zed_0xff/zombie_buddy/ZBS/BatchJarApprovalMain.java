package me.zed_0xff.zombie_buddy;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone entry point for a non-headless JVM: shows one Swing window listing
 * all Java mods that need approval. Invoked by {@link Loader} via ProcessBuilder.
 *
 * <p>Args: {@code <requestFile> <responseFile>}
 */
public final class BatchJarApprovalMain {

    private static final Color ZBS_ROW_OK = new Color(220, 255, 220);
    private static final Color ZBS_ROW_BAD = new Color(255, 210, 210);
    private static final Color STATUS_UNKNOWN_BG = new Color(255, 244, 176);

    private BatchJarApprovalMain() {}

    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            System.err.println("Usage: BatchJarApprovalMain <requestFile> <responseFile>");
            System.exit(2);
        }
        Path req = Paths.get(args[0]);
        Path resp = Paths.get(args[1]);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        try {
            final List<JarBatchApprovalProtocol.Entry> entries = JarBatchApprovalProtocol.readRequest(req);
            if (entries.isEmpty()) {
                JarBatchApprovalProtocol.writeResponse(resp, new ArrayList<>());
                System.exit(0);
                return;
            }

            // Author column: SteamID64 → label via SteamAuthorNames (GitHub + ~/.zombie_buddy cache).
            final Map<String, String> steamIdToDisplayName = SteamAuthorNames.loadSteamIdToDisplayName();
            SwingUtilities.invokeLater(() -> showDialog(entries, resp, steamIdToDisplayName));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static String modTitle(JarBatchApprovalProtocol.Entry e) {
        String d = e.modDisplayName;
        if (d != null && !d.trim().isEmpty()) {
            return d;
        }
        return e.modId != null && !e.modId.isEmpty() ? e.modId : e.modKey;
    }

    private static void showDialog(
        List<JarBatchApprovalProtocol.Entry> entries,
        Path resp,
        Map<String, String> steamIdToDisplayName
    ) {
        JFrame frame = new JFrame("ZombieBuddy — Java mod approval " + JarBatchApprovalProtocol.osTag());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.exit(2);
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel("<html>For each mod choose <b>Yes</b> (load JAR) or <b>No</b> (block).</html>");
        root.add(intro, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 8, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;

        Font base = UIManager.getFont("Label.font");
        Font bold = base != null ? base.deriveFont(Font.BOLD) : null;

        JLabel hName = new JLabel("Mod name");
        JLabel hAuthor = new JLabel("Author");
        JLabel hUpdated = new JLabel("Updated");
        JLabel hSteamBan = new JLabel("<html><center>Steam<br/>ban status</center></html>");
        JLabel hTrust = new JLabel("<html><center>Trust<br/>author</center></html>");
        JLabel hAllow = new JLabel("Allow");
        hUpdated.setHorizontalAlignment(SwingConstants.CENTER);
        hSteamBan.setHorizontalAlignment(SwingConstants.CENTER);
        hAllow.setHorizontalAlignment(SwingConstants.CENTER);
        hTrust.setHorizontalAlignment(SwingConstants.CENTER);
        if (bold != null) {
            hName.setFont(bold);
            hAuthor.setFont(bold);
            hUpdated.setFont(bold);
            hSteamBan.setFont(bold);
            hTrust.setFont(bold);
            hAllow.setFont(bold);
        }
        c.gridx = 0;
        c.weightx = 0.24;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(hName, c);
        c.gridx = 1;
        c.weightx = 0.27;
        grid.add(hAuthor, c);
        c.gridx = 2;
        c.weightx = 0.14;
        grid.add(hUpdated, c);
        c.gridx = 3;
        c.weightx = 0.14;
        grid.add(hSteamBan, c);
        c.gridx = 4;
        c.weightx = 0.09;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(hAllow, c);
        c.gridx = 5;
        c.weightx = 0.12;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(hTrust, c);

        @SuppressWarnings("unchecked")
        final JRadioButton[] allowYes = new JRadioButton[entries.size()];
        @SuppressWarnings("unchecked")
        final JRadioButton[] allowNo = new JRadioButton[entries.size()];
        @SuppressWarnings("unchecked")
        final JCheckBox[] trustChecks = new JCheckBox[entries.size()];
        final boolean[] initialAllowYes = new boolean[entries.size()];
        final String[] authorGroupKey = new String[entries.size()];

        int i = 0;
        for (JarBatchApprovalProtocol.Entry e : entries) {
            boolean zbsYes = "yes".equals(e.zbsValid);
            boolean zbsNo = "no".equals(e.zbsValid);
            boolean zbsUnsigned = "unsigned".equals(e.zbsValid);
            boolean steamBanYes = "yes".equals(e.steamBanStatus);
            boolean steamBanUnknown = "unknown".equals(e.steamBanStatus);
            Color rowBg = steamBanYes ? ZBS_ROW_BAD : (zbsYes ? ZBS_ROW_OK : (zbsNo ? ZBS_ROW_BAD : null));

            c.gridy = i + 1;
            c.gridx = 0;
            c.weightx = 0.26;
            c.fill = GridBagConstraints.HORIZONTAL;
            JLabel nameLab = new JLabel(modTitle(e));
            String tip = "<html>" + escapeHtml(e.jarAbsolutePath)
                + "<br/><b>SHA-256:</b> " + escapeHtml(e.sha256) + "</html>";
            nameLab.setToolTipText(tip);
            if (rowBg != null) {
                nameLab.setOpaque(true);
                nameLab.setBackground(rowBg);
            }
            grid.add(nameLab, c);

            c.gridx = 1;
            c.weightx = 0.30;
            JPanel authorCell = new JPanel();
            authorCell.setLayout(new BoxLayout(authorCell, BoxLayout.PAGE_AXIS));
            authorCell.setOpaque(rowBg != null);
            if (rowBg != null) {
                authorCell.setBackground(rowBg);
            }
            if (zbsYes && e.zbsSteamId != null && !e.zbsSteamId.isEmpty()) {
                String profileUrl = ZBSVerifier.steamCommunityProfileUrl(e.zbsSteamId);
                String resolved = steamIdToDisplayName != null
                    ? steamIdToDisplayName.get(e.zbsSteamId)
                    : null;
                String linkText = resolved != null && !resolved.isEmpty() ? resolved : e.zbsSteamId;
                JLabel linkLab = new JLabel(
                    "<html><a href=\"" + profileUrl + "\">" + escapeHtml(linkText) + "</a></html>");
                linkLab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                if (!e.zbsSteamId.equals(linkText)) {
                    linkLab.setToolTipText(e.zbsSteamId);
                }
                linkLab.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent ev) {
                        openUri(profileUrl);
                    }
                });
                if (rowBg != null) {
                    linkLab.setOpaque(true);
                    linkLab.setBackground(rowBg);
                }
                authorCell.add(linkLab);
            } else if (zbsNo) {
                JLabel warn = new JLabel("<html><font color=\"#b00000\">" + escapeHtml(
                    e.zbsNotice != null && !e.zbsNotice.isEmpty()
                        ? e.zbsNotice
                        : "Invalid signature — JAR may have been tampered with."
                ) + "</font></html>");
                warn.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (rowBg != null) {
                    warn.setOpaque(true);
                    warn.setBackground(rowBg);
                }
                authorCell.add(warn);
            } else if (zbsUnsigned) {
                JLabel u = new JLabel("<html><i>(unsigned)</i></html>");
                u.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (rowBg != null) {
                    u.setOpaque(true);
                    u.setBackground(rowBg);
                }
                authorCell.add(u);
            } else {
                String authorText = "?";
                JLabel plain = new JLabel(authorText);
                if (rowBg != null) {
                    plain.setOpaque(true);
                    plain.setBackground(rowBg);
                }
                authorCell.add(plain);
            }
            grid.add(authorCell, c);

            c.gridx = 2;
            c.weightx = 0.14;
            JLabel dateLab = new JLabel(e.modifiedHuman != null && !e.modifiedHuman.isEmpty() ? e.modifiedHuman : "—");
            if (rowBg != null) {
                dateLab.setOpaque(true);
                dateLab.setBackground(rowBg);
            }
            grid.add(dateLab, c);

            c.gridx = 3;
            c.weightx = 0.14;
            JLabel banStatusLab = new JLabel(steamBanYes ? "Yes" : (steamBanUnknown ? "Unknown" : "No"));
            if (steamBanYes) {
                banStatusLab.setForeground(new Color(176, 0, 0));
            } else if (steamBanUnknown) {
                banStatusLab.setOpaque(true);
                banStatusLab.setBackground(STATUS_UNKNOWN_BG);
            } else if (rowBg != null) {
                banStatusLab.setOpaque(true);
                banStatusLab.setBackground(rowBg);
            }
            if (e.steamBanReason != null && !e.steamBanReason.isEmpty()) {
                banStatusLab.setToolTipText(escapeHtml(e.steamBanReason));
            }
            grid.add(banStatusLab, c);

            boolean defaultYes = Loader.DECISION_YES.equals(e.priorHint);
            JRadioButton yesB = new JRadioButton("Yes", defaultYes);
            JRadioButton noB = new JRadioButton("No", !defaultYes);
            if (zbsNo || steamBanYes) {
                yesB.setEnabled(false);
                noB.setEnabled(false);
                noB.setSelected(true);
            }
            ButtonGroup grp = new ButtonGroup();
            grp.add(yesB);
            grp.add(noB);
            allowYes[i] = yesB;
            allowNo[i] = noB;
            initialAllowYes[i] = yesB.isSelected();

            JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            radios.setOpaque(rowBg != null);
            if (rowBg != null) {
                radios.setBackground(rowBg);
            }
            radios.add(yesB);
            radios.add(noB);
            c.gridx = 4;
            c.weightx = 0.0;
            c.fill = GridBagConstraints.NONE;
            grid.add(radios, c);

            c.gridx = 5;
            c.weightx = 0.12;
            c.fill = GridBagConstraints.HORIZONTAL;
            JCheckBox trustCb = new JCheckBox("", false);
            trustCb.setEnabled(zbsYes && !steamBanYes);
            trustCb.setOpaque(rowBg != null);
            if (rowBg != null) {
                trustCb.setBackground(rowBg);
            }
            trustChecks[i] = trustCb;
            authorGroupKey[i] = e.zbsSteamId != null ? e.zbsSteamId : "";
            grid.add(trustCb, c);
            i++;
        }
        Map<String, List<Integer>> authorGroups = new HashMap<>();
        for (int idx = 0; idx < entries.size(); idx++) {
            if (!trustChecks[idx].isEnabled()) {
                continue;
            }
            String key = authorGroupKey[idx];
            if (key == null || key.isEmpty()) {
                continue;
            }
            authorGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(idx);
        }
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(960, 420));
        root.add(scroll, BorderLayout.CENTER);

        JCheckBox savePersist = new JCheckBox(
            "Save decisions to disk (persist across game launches)", true);
        savePersist.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel trustNotice = new JLabel(
            "<html><small><i>\"Trust author\" means all mods by that author are auto-allowed while their digital signature remains valid and the mod is not banned.</i></small></html>");
        trustNotice.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        buttons.add(cancel);
        buttons.add(ok);
        Runnable updateOkEnabled = () -> {
            for (int idx = 0; idx < entries.size(); idx++) {
                if (!allowYes[idx].isSelected() && !allowNo[idx].isSelected()) {
                    ok.setEnabled(false);
                    return;
                }
            }
            ok.setEnabled(true);
        };
        for (int idx = 0; idx < entries.size(); idx++) {
            allowYes[idx].addItemListener(ev -> updateOkEnabled.run());
            allowNo[idx].addItemListener(ev -> updateOkEnabled.run());
        }
        final boolean[] syncingTrust = new boolean[] { false };
        for (int idx = 0; idx < entries.size(); idx++) {
            final int sourceIdx = idx;
            trustChecks[idx].addItemListener(ev -> {
                if (syncingTrust[0]) {
                    return;
                }
                boolean selected = trustChecks[sourceIdx].isSelected();
                String key = authorGroupKey[sourceIdx];
                if (key == null || key.isEmpty()) {
                    if (selected || initialAllowYes[sourceIdx]) {
                        allowYes[sourceIdx].setSelected(true);
                    } else {
                        allowNo[sourceIdx].setSelected(true);
                    }
                    updateOkEnabled.run();
                    return;
                }
                List<Integer> group = authorGroups.get(key);
                if (group == null || group.isEmpty()) {
                    return;
                }
                syncingTrust[0] = true;
                try {
                    for (Integer row : group) {
                        trustChecks[row].setSelected(selected);
                        if (selected || initialAllowYes[row]) {
                            allowYes[row].setSelected(true);
                        } else {
                            allowNo[row].setSelected(true);
                        }
                    }
                } finally {
                    syncingTrust[0] = false;
                }
                updateOkEnabled.run();
            });
        }
        updateOkEnabled.run();

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.PAGE_AXIS));
        south.add(savePersist);
        south.add(Box.createVerticalStrut(6));
        south.add(trustNotice);
        south.add(Box.createVerticalStrut(8));
        south.add(buttons);
        root.add(south, BorderLayout.SOUTH);

        cancel.addActionListener(ev -> System.exit(2));
        ok.addActionListener(ev -> {
            try {
                boolean persist = savePersist.isSelected();
                List<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(entries.size());
                for (int k = 0; k < entries.size(); k++) {
                    JarBatchApprovalProtocol.Entry e = entries.get(k);
                    String tok;
                    if ("no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus)) {
                        // Always deny loading; same session/persist split as other "No" rows.
                        tok = persist
                            ? JarBatchApprovalProtocol.TOK_DENY_PERSIST
                            : JarBatchApprovalProtocol.TOK_DENY_SESSION;
                    } else {
                        boolean allow = allowYes[k].isSelected();
                        if (allow && persist) {
                            tok = JarBatchApprovalProtocol.TOK_ALLOW_PERSIST;
                        } else if (allow) {
                            tok = JarBatchApprovalProtocol.TOK_ALLOW_SESSION;
                        } else if (persist) {
                            tok = JarBatchApprovalProtocol.TOK_DENY_PERSIST;
                        } else {
                            tok = JarBatchApprovalProtocol.TOK_DENY_SESSION;
                        }
                    }
                    String trustedAuthorSteamId = "";
                    if (persist && trustChecks[k].isSelected() && "yes".equals(e.zbsValid)) {
                        trustedAuthorSteamId = e.zbsSteamId != null ? e.zbsSteamId : "";
                    }
                    out.add(new JarBatchApprovalProtocol.OutLine(e.modKey, e.sha256, tok, trustedAuthorSteamId));
                }
                JarBatchApprovalProtocol.writeResponse(resp, out);
                System.exit(0);
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
                System.exit(2);
            }
        });

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void openUri(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ignored) {
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
