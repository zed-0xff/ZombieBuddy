package me.zed_0xff.zombie_buddy.frontend;

import static me.zed_0xff.zombie_buddy.ModFlags.MF_PERSIST;
import static me.zed_0xff.zombie_buddy.ModFlags.MF_TRUST_AUTHOR;
import static me.zed_0xff.zombie_buddy.SteamWorkshop.SteamID64;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.KnownAuthors;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.Utils;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Standalone entry point for a non-headless JVM: shows one Swing window listing
 * all Java mods that need approval. Invoked by {@link me.zed_0xff.zombie_buddy.Loader} via ProcessBuilder.
 *
 * <p>Args: {@code <requestFile> <responseFile>}
 */
public final class SwingApprovalMain {

    private static final Color ZBS_ROW_OK = new Color(220, 255, 220);
    private static final Color ZBS_ROW_BAD = new Color(255, 210, 210);
    private static final Color STEAM_BAN_NO = new Color(0, 128, 0);
    private static final String DATE_FORMAT = "yyyy-MM-dd";
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

    private SwingApprovalMain() {}

    public static void main(String[] args) {
        local.zbselective.i18n.UiText.initialize();
        if (args == null || args.length != 2) {
            System.err.println(local.zbselective.i18n.UiText.text("Usage: SwingApprovalMain <requestFile> <responseFile>", "用法: SwingApprovalMain <请求文件> <响应文件>"));
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

            // Author column: SteamID64 → label via KnownAuthors (GitHub + ~/.zombie_buddy cache).
            final Map<SteamID64, String> steamIdToDisplayName = KnownAuthors.loadSteamIdToDisplayName();
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
        return e.modId;
    }

    private static void showDialog(
        List<JarBatchApprovalProtocol.Entry> entries,
        Path resp,
        Map<SteamID64, String> steamIdToDisplayName
    ) {
        final Set<String> authorsWithBannedMods = new HashSet<>();
        for (JarBatchApprovalProtocol.Entry e : entries) {
            if (e.steamBan != null && e.zbs.authorSteamId() != null) {
                authorsWithBannedMods.add(e.zbs.authorSteamId().toString());
            }
        }
        final boolean showTrustColumn = shouldShowTrustColumn(entries, authorsWithBannedMods);
        JFrame frame = new JFrame(local.zbselective.i18n.UiText.text("ZombieBuddy — Java mod approval", "ZombieBuddy — Java 模组审批"));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.exit(2);
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel intro = new JLabel(local.zbselective.i18n.UiText.text("<html>For each mod choose <b>Yes</b> (load JAR) or <b>No</b> (block).</html>", "<html>请为每个模组选择<b>是</b>（加载 JAR）或<b>否</b>（阻止）。</html>"));
        root.add(intro, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = HEADER_INSETS;
        c.anchor = GridBagConstraints.WEST;
        c.gridy = 0;

        Font base = UIManager.getFont("Label.font");
        Font bold = base != null ? base.deriveFont(Font.BOLD) : null;

        JLabel hName     = new JLabel(local.zbselective.i18n.UiText.text("Mod", "模组"));
        JLabel hAuthor   = new JLabel(local.zbselective.i18n.UiText.text("Author", "作者"));
        JLabel hUpdated  = new JLabel(local.zbselective.i18n.UiText.text("Updated", "更新时间"));
        JLabel hSteamBan = new JLabel(local.zbselective.i18n.UiText.text("<html><center>Steam<br/>ban status</center></html>", "<html><center>Steam<br/>封禁状态</center></html>"));
        JLabel hTrust    = new JLabel(local.zbselective.i18n.UiText.text("<html><center>Trust<br/>author</center></html>", "<html><center>信任<br/>作者</center></html>"));
        JLabel hAllow    = new JLabel(local.zbselective.i18n.UiText.text("Allow", "允许"));

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

        final JRadioButton[] allowYes = new JRadioButton[entries.size()];
        final JRadioButton[] allowNo = new JRadioButton[entries.size()];
        final JCheckBox[] trustChecks = new JCheckBox[entries.size()];
        final boolean[] initialAllowYes = new boolean[entries.size()];
        final boolean[] forceDisableAllow = new boolean[entries.size()];
        final String[] authorGroupKey = new String[entries.size()];
        c.insets = ROW_INSETS;

        int i = 0;
        for (JarBatchApprovalProtocol.Entry e : entries) {
            boolean zbsYes = e.zbs.valid();
            boolean zbsNo = e.zbs.invalid();
            boolean zbsUnsigned = e.zbs.unsigned();
            boolean steamBanYes = e.steamBan != null;
            Color rowBg = steamBanYes ? ZBS_ROW_BAD : (zbsYes ? ZBS_ROW_OK : (zbsNo ? ZBS_ROW_BAD : null));

            c.gridy = i + 1;
            c.gridx = COL_MOD;
            c.weightx = W_MOD_ROW;
            c.fill = GridBagConstraints.BOTH;
            JLabel nameLab;
            if (e.workshopItemId != null) {
                String workshopUrl = SteamWorkshop.workshopItemUrl(e.workshopItemId);
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
            String tip =
                "<html>"
                + escapeHtml(e.jarAbsolutePath.toString()) + "<br/>"
                + "<b>SHA-256:</b> " + escapeHtml(e.sha256)
                + "</html>";
            nameLab.setToolTipText(tip);
            applyRowBackground(nameLab, rowBg);
            grid.add(nameLab, c);

            c.gridx = COL_AUTHOR;
            c.weightx = W_AUTHOR_ROW;
            c.fill = GridBagConstraints.BOTH;
            JPanel authorCell = new JPanel();
            authorCell.setLayout(new BoxLayout(authorCell, BoxLayout.PAGE_AXIS));
            applyRowBackground(authorCell, rowBg);
            String zbsSteamId = e.zbs.authorSteamId() != null ? e.zbs.authorSteamId().toString() : "";
            if (zbsYes && !zbsSteamId.isEmpty()) {
                String profileUrl = SteamWorkshop.authorWorkshopUrl(e.zbs.authorSteamId());
                String resolved = steamIdToDisplayName != null
                    ? steamIdToDisplayName.get(e.zbs.authorSteamId())
                    : null;
                String linkText = !Utils.isBlank(resolved) ? resolved : zbsSteamId;
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
                String fullNotice = !Utils.isBlank(e.zbs.notice())
                    ? e.zbs.notice()
                    : local.zbselective.i18n.UiText.text("Invalid signature — JAR may have been tampered with.", "签名无效——JAR 可能已被篡改。");
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
                JLabel u = new JLabel(local.zbselective.i18n.UiText.text("<html><i>(unsigned)</i></html>", "<html><i>（未签名）</i></html>"));
                u.setAlignmentX(Component.LEFT_ALIGNMENT);
                applyRowBackground(u, rowBg);
                authorCell.add(u);
            } else {
                String authorText = local.zbselective.i18n.UiText.text("?", "？");
                JLabel plain = new JLabel(authorText);
                applyRowBackground(plain, rowBg);
                authorCell.add(plain);
            }
            grid.add(authorCell, c);

            c.gridx = COL_UPDATED;
            c.weightx = W_UPDATED;
            c.fill = GridBagConstraints.BOTH;
            JLabel dateLab = new JLabel(formatDate(e.date));
            dateLab.setHorizontalAlignment(SwingConstants.CENTER);
            applyRowBackground(dateLab, rowBg);
            grid.add(dateLab, c);

            c.gridx = COL_STEAM_BAN;
            c.weightx = W_STEAM_BAN;
            c.fill = GridBagConstraints.BOTH;
            JLabel banStatusLab = new JLabel(steamBanYes ? local.zbselective.i18n.UiText.text("Yes", "是") : local.zbselective.i18n.UiText.text("No", "否"));
            banStatusLab.setHorizontalAlignment(SwingConstants.CENTER);
            if (!steamBanYes) {
                banStatusLab.setForeground(STEAM_BAN_NO);
            }
            applyRowBackground(banStatusLab, rowBg);
            if (e.steamBan != null && !Utils.isBlank(e.steamBan.reason())) {
                banStatusLab.setToolTipText(escapeHtml(e.steamBan.reason()));
            }
            grid.add(banStatusLab, c);

            boolean defaultYes = Boolean.TRUE.equals(e.decision);
            JRadioButton yesB = new JRadioButton(local.zbselective.i18n.UiText.text("Yes", "是"), defaultYes);
            JRadioButton noB = new JRadioButton(local.zbselective.i18n.UiText.text("No", "否"), !defaultYes);
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

            JPanel radios = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
            applyRowBackground(radios, rowBg);
            radios.add(yesB);
            radios.add(noB);
            c.gridx = COL_ALLOW;
            c.weightx = showTrustColumn ? W_ALLOW_WITH_TRUST : W_ALLOW_NO_TRUST;
            c.fill = GridBagConstraints.BOTH;
            grid.add(radios, c);

            boolean canTrustThisAuthor = showTrustColumn && zbsYes && !steamBanYes
                && (zbsSteamId.isEmpty() || !authorsWithBannedMods.contains(zbsSteamId));
            JCheckBox trustCb = new JCheckBox("", canTrustThisAuthor && e.flags.has(MF_TRUST_AUTHOR));
            trustCb.setEnabled(canTrustThisAuthor);
            if (!canTrustThisAuthor && showTrustColumn && zbsYes && !zbsSteamId.isEmpty()
                    && authorsWithBannedMods.contains(zbsSteamId)) {
                trustCb.setToolTipText(local.zbselective.i18n.UiText.text("Cannot trust author: they have a banned mod in this batch.", "无法信任该作者：其在本批次中有被封禁的模组。"));
            }
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
            if (Utils.isBlank(key)) {
                continue;
            }
            authorGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(idx);
        }
        JScrollPane scroll = new JScrollPane(grid);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(960, 420));
        root.add(scroll, BorderLayout.CENTER);

        JLabel trustNotice = new JLabel(
            local.zbselective.i18n.UiText.text("<html><small><i>\"Trust author\" means all mods by that author are auto-allowed while their digital signature remains valid and the mod is not banned.</i></small></html>", "<html><small><i>“信任作者”表示该作者的所有模组将在其数字签名仍然有效且未被封禁时自动允许加载。</i></small></html>"));
        trustNotice.setAlignmentX(Component.LEFT_ALIGNMENT);
        JCheckBox savePersist = new JCheckBox(local.zbselective.i18n.UiText.text("Save decisions to disk (persist across game launches)", "将决定保存到磁盘（跨游戏启动保持）"), false);
        savePersist.setHorizontalTextPosition(SwingConstants.LEADING);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton(local.zbselective.i18n.UiText.text("OK", "确定"));
        JButton cancel = new JButton(local.zbselective.i18n.UiText.text("Cancel", "取消"));
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
                if (Utils.isBlank(key)) {
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
                if (Utils.isBlank(group)) {
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
        // Apply initial allow state for rows where trust author was pre-checked from stored state.
        for (int idx = 0; idx < entries.size(); idx++) {
            if (trustChecks[idx].isSelected() && trustChecks[idx].isEnabled()) {
                setAllowStateForTrustRow(idx, true, initialAllowYes, forceDisableAllow, allowYes, allowNo);
            }
        }
        updateOkEnabled.run();

        JPanel persistRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        persistRow.add(savePersist);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.PAGE_AXIS));
        south.add(persistRow);
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
                List<JarBatchApprovalProtocol.Entry> out = new ArrayList<>(entries.size());
                for (int k = 0; k < entries.size(); k++) {
                    JarBatchApprovalProtocol.Entry e = entries.get(k);
                    boolean allow;
                    if (e.zbs.invalid() || e.steamBan != null) {
                        // Always deny rows that cannot be loaded safely.
                        allow = false;
                    } else {
                        allow = allowYes[k].isSelected();
                    }
                    e.decision = allow;
                    if (savePersist.isSelected()) {
                        e.flags = e.flags.with(MF_PERSIST);
                    } else {
                        e.flags = e.flags.without(MF_PERSIST);
                    }
                    if (savePersist.isSelected() && trustChecks[k].isSelected() && trustChecks[k].isEnabled()) {
                        e.flags = e.flags.with(MF_TRUST_AUTHOR);
                    } else {
                        e.flags = e.flags.without(MF_TRUST_AUTHOR);
                    }
                    out.add(e);
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

    private static void applyRowBackground(JComponent component, Color rowBg) {
        component.setOpaque(rowBg != null);
        if (rowBg != null) {
            component.setBackground(rowBg);
        }
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return "—";
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
    }

    private static boolean shouldShowTrustColumn(
            List<JarBatchApprovalProtocol.Entry> entries, Set<String> authorsWithBannedMods) {
        for (JarBatchApprovalProtocol.Entry e : entries) {
            if (e.zbs.valid() && e.steamBan == null && e.zbs.authorSteamId() != null
                    && !authorsWithBannedMods.contains(e.zbs.authorSteamId().toString())) {
                return true;
            }
        }
        return false;
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
