package me.zed_0xff.zombie_buddy;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
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
    private static final Insets HEADER_INSETS = new Insets(3, 8, 3, 8);
    private static final Insets ROW_INSETS = new Insets(0, 0, 0, 0);
    private static final int COL_MOD = 0;
    private static final int COL_AUTHOR = 1;
    private static final int COL_UPDATED = 2;
    private static final int COL_STEAM_BAN = 3;
    private static final int COL_ALLOW = 4;
    private static final int COL_TRUST = 5;
    private static final double W_MOD_HEADER = 0.24;
    private static final double W_AUTHOR_HEADER = 0.27;
    private static final double W_UPDATED = 0.14;
    private static final double W_STEAM_BAN = 0.14;
    private static final double W_ALLOW_WITH_TRUST = 0.09;
    private static final double W_ALLOW_NO_TRUST = 0.21;
    private static final double W_TRUST = 0.12;
    private static final double W_MOD_ROW = 0.26;
    private static final double W_AUTHOR_ROW = 0.30;

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
            final Map<SteamID64, String> steamIdToDisplayName = SteamAuthorNames.loadSteamIdToDisplayName();
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
        Map<SteamID64, String> steamIdToDisplayName
    ) {
        final boolean showTrustColumn = entries.stream().anyMatch(e -> "yes".equals(e.zbsValid));
        JFrame frame = new JFrame("ZombieBuddy — Java mod approval");
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
        c.insets = HEADER_INSETS;
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;

        Font base = UIManager.getFont("Label.font");
        Font bold = base != null ? base.deriveFont(Font.BOLD) : null;

        JLabel hName     = new JLabel("Mod");
        JLabel hAuthor   = new JLabel("Author");
        JLabel hUpdated  = new JLabel("Updated");
        JLabel hSteamBan = new JLabel("<html><center>Steam<br/>ban status</center></html>");
        JLabel hTrust    = new JLabel("<html><center>Trust<br/>author</center></html>");
        JLabel hAllow    = new JLabel("Allow");

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

        c.gridx = COL_MOD;
        c.weightx = W_MOD_HEADER;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(hName, c);
        c.gridx = COL_AUTHOR;
        c.weightx = W_AUTHOR_HEADER;
        grid.add(hAuthor, c);
        c.gridx = COL_UPDATED;
        c.weightx = W_UPDATED;
        grid.add(hUpdated, c);
        c.gridx = COL_STEAM_BAN;
        c.weightx = W_STEAM_BAN;
        grid.add(hSteamBan, c);
        c.gridx = COL_ALLOW;
        c.weightx = showTrustColumn ? W_ALLOW_WITH_TRUST : W_ALLOW_NO_TRUST;
        c.fill = GridBagConstraints.HORIZONTAL;
        grid.add(hAllow, c);

        if (showTrustColumn) {
            c.gridx = COL_TRUST;
            c.weightx = W_TRUST;
            c.fill = GridBagConstraints.HORIZONTAL;
            grid.add(hTrust, c);
        }

        @SuppressWarnings("unchecked")
        final JRadioButton[] allowYes = new JRadioButton[entries.size()];
        @SuppressWarnings("unchecked")
        final JRadioButton[] allowNo = new JRadioButton[entries.size()];
        @SuppressWarnings("unchecked")
        final JCheckBox[] trustChecks = new JCheckBox[entries.size()];
        final boolean[] initialAllowYes = new boolean[entries.size()];
        final boolean[] forceDisableAllow = new boolean[entries.size()];
        final String[] authorGroupKey = new String[entries.size()];
        c.insets = ROW_INSETS;

        int i = 0;
        for (JarBatchApprovalProtocol.Entry e : entries) {
            boolean zbsYes = "yes".equals(e.zbsValid);
            boolean zbsNo = "no".equals(e.zbsValid);
            boolean zbsUnsigned = "unsigned".equals(e.zbsValid);
            boolean steamBanYes = "yes".equals(e.steamBanStatus);
            boolean steamBanUnknown = "unknown".equals(e.steamBanStatus);
            Color rowBg = steamBanYes ? ZBS_ROW_BAD : (zbsYes ? ZBS_ROW_OK : (zbsNo ? ZBS_ROW_BAD : null));

            c.gridy = i + 1;
            c.gridx = COL_MOD;
            c.weightx = W_MOD_ROW;
            c.fill = GridBagConstraints.BOTH;
            String workshopItemId = e.workshopItemId != null ? Long.toString(e.workshopItemId.value()) : null;
            JLabel nameLab;
            if (workshopItemId != null) {
                String workshopUrl = workshopItemUrl(workshopItemId);
                nameLab = new JLabel("<html><a href=\"" + workshopUrl + "\">" + escapeHtml(modTitle(e)) + "</a></html>");
                nameLab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                nameLab.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent ev) {
                        openUri(workshopUrl);
                    }
                });
            } else {
                nameLab = new JLabel(modTitle(e));
            }
            String tip = "<html>" + escapeHtml(e.jarAbsolutePath)
                + "<br/><b>SHA-256:</b> " + escapeHtml(e.sha256) + "</html>";
            nameLab.setToolTipText(tip);
            applyRowBackground(nameLab, rowBg);
            grid.add(nameLab, c);

            c.gridx = COL_AUTHOR;
            c.weightx = W_AUTHOR_ROW;
            c.fill = GridBagConstraints.BOTH;
            JPanel authorCell = new JPanel();
            authorCell.setLayout(new BoxLayout(authorCell, BoxLayout.PAGE_AXIS));
            applyRowBackground(authorCell, rowBg);
            String zbsSteamId = e.zbsSteamId != null ? e.zbsSteamId.value() : "";
            if (zbsYes && !zbsSteamId.isEmpty()) {
                String profileUrl = ZBSVerifier.steamProfileUrl(zbsSteamId);
                String resolved = steamIdToDisplayName != null
                    ? steamIdToDisplayName.get(e.zbsSteamId)
                    : null;
                String linkText = resolved != null && !resolved.isEmpty() ? resolved : zbsSteamId;
                JLabel linkLab = new JLabel(
                    "<html><a href=\"" + profileUrl + "\">" + escapeHtml(linkText) + "</a></html>");
                linkLab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                if (!zbsSteamId.equals(linkText)) {
                    linkLab.setToolTipText(zbsSteamId);
                }
                linkLab.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent ev) {
                        openUri(profileUrl);
                    }
                });
                applyRowBackground(linkLab, rowBg);
                authorCell.add(linkLab);
            } else if (zbsNo) {
                String fullNotice = e.zbsNotice != null && !e.zbsNotice.isEmpty()
                    ? e.zbsNotice
                    : "Invalid signature — JAR may have been tampered with.";
                int nl = fullNotice.indexOf('\n');
                String shortNotice = nl >= 0 ? fullNotice.substring(0, nl).trim() : fullNotice;
                JLabel warn = new JLabel("<html><font color=\"#b00000\">" + escapeHtml(
                    shortNotice
                ) + "</font></html>");
                if (nl >= 0 && nl < fullNotice.length() - 1) {
                    warn.setToolTipText("<html>" + escapeHtml(fullNotice).replace("\n", "<br/>") + "</html>");
                }
                warn.setAlignmentX(Component.LEFT_ALIGNMENT);
                applyRowBackground(warn, rowBg);
                authorCell.add(warn);
            } else if (zbsUnsigned) {
                JLabel u = new JLabel("<html><i>(unsigned)</i></html>");
                u.setAlignmentX(Component.LEFT_ALIGNMENT);
                applyRowBackground(u, rowBg);
                authorCell.add(u);
            } else {
                String authorText = "?";
                JLabel plain = new JLabel(authorText);
                applyRowBackground(plain, rowBg);
                authorCell.add(plain);
            }
            grid.add(authorCell, c);

            c.gridx = COL_UPDATED;
            c.weightx = W_UPDATED;
            c.fill = GridBagConstraints.BOTH;
            JLabel dateLab = new JLabel(e.modifiedHuman != null && !e.modifiedHuman.isEmpty() ? e.modifiedHuman : "—");
            applyRowBackground(dateLab, rowBg);
            grid.add(dateLab, c);

            c.gridx = COL_STEAM_BAN;
            c.weightx = W_STEAM_BAN;
            c.fill = GridBagConstraints.BOTH;
            JLabel banStatusLab = new JLabel(steamBanYes ? "Yes" : (steamBanUnknown ? "Unknown" : "No"));
            applyRowBackground(banStatusLab, rowBg);
            if (e.steamBanReason != null && !e.steamBanReason.isEmpty()) {
                banStatusLab.setToolTipText(escapeHtml(e.steamBanReason));
            }
            grid.add(banStatusLab, c);

            boolean defaultYes = Loader.DECISION_YES.equals(e.priorHint);
            JRadioButton yesB = new JRadioButton("Yes", defaultYes);
            JRadioButton noB = new JRadioButton("No", !defaultYes);
            forceDisableAllow[i] = zbsNo || steamBanYes;
            if (forceDisableAllow[i]) {
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
            applyRowBackground(radios, rowBg);
            radios.add(yesB);
            radios.add(noB);
            c.gridx = COL_ALLOW;
            c.weightx = 0.0;
            c.fill = GridBagConstraints.BOTH;
            grid.add(radios, c);

            JCheckBox trustCb = new JCheckBox("", false);
            trustCb.setEnabled(showTrustColumn && zbsYes && !steamBanYes);
            applyRowBackground(trustCb, rowBg);
            trustChecks[i] = trustCb;
            authorGroupKey[i] = zbsSteamId;
            if (showTrustColumn) {
                JPanel trustCell = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
                applyRowBackground(trustCell, rowBg);
                trustCell.add(trustCb);
                c.gridx = COL_TRUST;
                c.weightx = W_TRUST;
                c.fill = GridBagConstraints.BOTH;
                grid.add(trustCell, c);
            }
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

        JLabel savePersistLabel = new JLabel("Save decisions to disk (persist across game launches)");
        JCheckBox savePersist = new JCheckBox("", true);
        savePersistLabel.setLabelFor(savePersist);
        JPanel savePersistRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        savePersistRow.add(savePersistLabel);
        savePersistRow.add(savePersist);
        JLabel trustNotice = new JLabel(
            "<html><small><i>\"Trust author\" means all mods by that author are auto-allowed while their digital signature remains valid and the mod is not banned.</i></small></html>");
        trustNotice.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        int checkboxShiftRight = Math.max(
            0,
            (ok.getPreferredSize().width - savePersist.getPreferredSize().width) / 2
        );
        savePersistRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, checkboxShiftRight));
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
                    setAllowStateForTrustRow(
                        sourceIdx,
                        selected,
                        initialAllowYes,
                        forceDisableAllow,
                        allowYes,
                        allowNo
                    );
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
                        setAllowStateForTrustRow(
                            row,
                            selected,
                            initialAllowYes,
                            forceDisableAllow,
                            allowYes,
                            allowNo
                        );
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
        south.add(savePersistRow);
        if (showTrustColumn) {
            south.add(Box.createVerticalStrut(6));
            south.add(trustNotice);
        }
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
                        trustedAuthorSteamId = e.zbsSteamId != null ? e.zbsSteamId.value() : "";
                    }
                    out.add(new JarBatchApprovalProtocol.OutLine(
                        e.modKey,
                        e.workshopItemId,
                        e.sha256,
                        tok,
                        trustedAuthorSteamId
                    ));
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

    private static String workshopItemUrl(String workshopItemId) {
        return "https://steamcommunity.com/sharedfiles/filedetails/?id=" + workshopItemId;
    }

    private static void applyRowBackground(JComponent component, Color rowBg) {
        component.setOpaque(rowBg != null);
        if (rowBg != null) {
            component.setBackground(rowBg);
        }
    }

    private static void setAllowStateForTrustRow(
        int row,
        boolean trustSelected,
        boolean[] initialAllowYes,
        boolean[] forceDisableAllow,
        JRadioButton[] allowYes,
        JRadioButton[] allowNo
    ) {
        if (trustSelected) {
            allowYes[row].setSelected(true);
            allowYes[row].setEnabled(false);
            allowNo[row].setEnabled(false);
            return;
        }
        if (forceDisableAllow[row]) {
            allowNo[row].setSelected(true);
            allowYes[row].setEnabled(false);
            allowNo[row].setEnabled(false);
            return;
        }
        if (initialAllowYes[row]) {
            allowYes[row].setSelected(true);
        } else {
            allowNo[row].setSelected(true);
        }
        allowYes[row].setEnabled(true);
        allowNo[row].setEnabled(true);
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
