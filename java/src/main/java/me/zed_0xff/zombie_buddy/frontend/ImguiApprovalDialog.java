package me.zed_0xff.zombie_buddy.frontend;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.JarApprovalOutcome;
import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.ModApprovalsStore;
import me.zed_0xff.zombie_buddy.SteamWorkshop;

import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableBgTarget;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiTableRowFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import zombie.network.DesktopBrowser;

final class ImguiApprovalDialog {
    private static final String PRIOR_YES = "yes";
    private static final int ROW_OK = ImColor.rgba(60, 110, 60, 80);
    private static final int ROW_BAD = ImColor.rgba(130, 55, 55, 95);
    private static final int STEAM_BAN_UNKNOWN = ImColor.rgb(184, 134, 11);
    private static final int STEAM_BAN_NO = ImColor.rgb(0, 170, 70);
    private static final int LINK = ImColor.rgb(80, 150, 255);
    private static final float DIALOG_W = 1024.0f;
    private static final float DIALOG_H = 768.0f;
    private static final float TABLE_ROW_MIN_HEIGHT = 32.0f;
    private static final String COL_MOD = "Mod";
    private static final String COL_AUTHOR = "Author";
    private static final String COL_UPDATED = "Updated";
    private static final String COL_STEAM_BAN = "Steam ban status";
    private static final String COL_ALLOW = "Allow";
    private static final String COL_TRUST_AUTHOR = "Trust author";
    private static final String TRUST_AUTHOR_TOOLTIP =
            "Signed mods by that author can be auto-allowed while the signature remains valid and the mod is not banned.";

    private final List<JarBatchApprovalProtocol.Entry> entries;
    private final ImBoolean[] allow;
    private final ImBoolean[] trustAuthor;
    private final boolean[] initialAllow;
    private final boolean[] forceDeny;
    private final String[] authorGroupKey;
    private final ImBoolean persist = new ImBoolean(true);
    private final ImBoolean open = new ImBoolean(true);
    private final AtomicReference<List<JarBatchApprovalProtocol.OutLine>> result;
    private final boolean showTrustColumn;
    private static float tableRowStartY;

    ImguiApprovalDialog(
            List<JarBatchApprovalProtocol.Entry> entries,
            AtomicReference<List<JarBatchApprovalProtocol.OutLine>> result) {
        this.entries = entries;
        this.result = result;
        this.allow = new ImBoolean[entries.size()];
        this.trustAuthor = new ImBoolean[entries.size()];
        this.initialAllow = new boolean[entries.size()];
        this.forceDeny = new boolean[entries.size()];
        this.authorGroupKey = new String[entries.size()];
        this.showTrustColumn = entries.stream().anyMatch(e -> "yes".equals(e.zbsValid));
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            this.initialAllow[i] = initialAllow(e);
            this.forceDeny[i] = "no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus);
            this.authorGroupKey[i] = e.zbsSteamId != null ? e.zbsSteamId.toString() : "";
            this.allow[i] = new ImBoolean(this.initialAllow[i]);
            this.trustAuthor[i] = new ImBoolean(false);
        }
    }

    boolean isOpen() {
        return open.get();
    }

    void close() {
        open.set(false);
    }

    void draw() {
        ImGui.getIO().setConfigWindowsMoveFromTitleBarOnly(true);
        ImGui.setNextWindowSize(DIALOG_W, DIALOG_H, ImGuiCond.Appearing);
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                ImGuiCond.Appearing,
                0.5f,
                0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowTitleAlign, 0.5f, 0.5f);
        boolean visible = ImGui.begin("ZombieBuddy Java Mod Approval", open, ImGuiWindowFlags.NoCollapse);
        ImGui.popStyleVar();
        if (!visible) {
            ImGui.end();
            return;
        }
        centeredText("Review each Java mod before allowing it to load.");
        ImGui.separator();

        if (ImGui.beginChild("##zb-imgui-approval-scroll", 0.0f, 420.0f, true)) {
            int columns = showTrustColumn ? 6 : 5;
            int tableFlags = ImGuiTableFlags.Borders
                    | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.Resizable
                    | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##zb-imgui-approval-table", columns, tableFlags)) {
                setupTableColumns();
                drawHeaderRow();
                for (int i = 0; i < entries.size(); i++) {
                    drawRow(i, entries.get(i));
                }
                ImGui.endTable();
            }
        }
        ImGui.endChild();

        ImGui.spacing();
        ImGui.spacing();
        drawBottomActions();
        ImGui.end();
    }

    private void drawBottomActions() {
        String persistLabel = "Save decisions to disk (persist across game launches)";
        String persistTooltip = "Saved to " + approvalsFilePath();
        String cancelLabel = "Cancel";
        String okLabel = "OK";

        float spacing = ImGui.getStyle().getItemSpacingX();
        float paddingX = ImGui.getStyle().getFramePaddingX();
        float checkboxW = ImGui.getFrameHeight();
        float buttonH = ImGui.getFrameHeight() * 1.35f;
        float persistLabelW = ImGui.calcTextSize(persistLabel).x;
        float cancelW = ImGui.calcTextSize(cancelLabel).x + paddingX * 4.0f;
        float okW = Math.max(80.0f, ImGui.calcTextSize(okLabel).x + paddingX * 4.0f);
        float persistRowW = persistLabelW + spacing + checkboxW;
        float buttonRowW = cancelW + spacing + okW;
        float rightPad = ImGui.getWindowWidth() - ImGui.getWindowContentRegionMaxX();

        ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - rightPad - persistRowW));
        ImGui.text(persistLabel);
        showTooltipIfHovered(persistTooltip);
        ImGui.sameLine();
        clickableCheckbox("##persist-decisions", persist);
        showTooltipIfHovered(persistTooltip);
        ImGui.spacing();

        ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - rightPad - buttonRowW));
        boolean cancelClicked = clickableButton(cancelLabel, cancelW, buttonH);
        showTooltipIfHovered("deny all for this game session");
        if (cancelClicked) {
            result.compareAndSet(null, denyAll(entries));
            close();
        }
        ImGui.sameLine();
        if (clickableButton(okLabel, okW, buttonH)) {
            result.compareAndSet(null, buildLines());
            close();
        }
    }

    private void drawHeaderRow() {
        ImGui.tableNextRow(ImGuiTableRowFlags.Headers, TABLE_ROW_MIN_HEIGHT);
        tableRowStartY = ImGui.getCursorPosY();
        ImGui.tableSetColumnIndex(0); cellCenteredText(COL_MOD);
        ImGui.tableSetColumnIndex(1); cellCenteredText(COL_AUTHOR);
        ImGui.tableSetColumnIndex(2); cellCenteredText(COL_UPDATED);
        ImGui.tableSetColumnIndex(3); cellCenteredText(COL_STEAM_BAN);
        ImGui.tableSetColumnIndex(4); cellCenteredText(COL_ALLOW);
        if (showTrustColumn) {
            ImGui.tableSetColumnIndex(5); cellCenteredText(COL_TRUST_AUTHOR);
        }
    }

    private static void centeredText(String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.text(text);
    }

    private static void centeredDisabledText(String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textDisabled(text);
    }

    private static void centeredTextColored(int color, String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(color, text);
    }

    private static void centeredTextColored(float r, float g, float b, float a, String text) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(r, g, b, a, text);
    }

    private static void cellTextWrapped(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        ImGui.textWrapped(text);
    }

    private static void cellCenteredText(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredText(text);
    }

    private static void cellCenteredDisabledText(String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredDisabledText(text);
    }

    private static void cellCenteredTextColored(int color, String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredTextColored(color, text);
    }

    private static void cellCenteredTextColored(float r, float g, float b, float a, String text) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        centeredTextColored(r, g, b, a, text);
    }

    private static void centerNextItem(float itemW) {
        float cellW = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0.0f, (cellW - itemW) * 0.5f));
    }

    private static void centerNextItemVertically(float itemH) {
        ImGui.setCursorPosY(tableRowStartY + Math.max(0.0f, (TABLE_ROW_MIN_HEIGHT - itemH) * 0.5f));
    }

    private static float contentColumnWidth(float contentW) {
        return contentW + ImGui.getStyle().getCellPaddingX() * 2.0f + ImGui.getStyle().getItemSpacingX();
    }

    private void setupTableColumns() {
        ImGui.tableSetupColumn(COL_MOD, ImGuiTableColumnFlags.WidthStretch, 1.0f);
        setupFixedColumn(COL_AUTHOR, columnContentWidth(COL_AUTHOR, e -> authorText(e)));
        setupFixedColumn(COL_UPDATED, columnContentWidth(COL_UPDATED, e -> updatedText(e)));
        setupFixedColumn(COL_STEAM_BAN, steamBanColumnContentWidth());
        setupFixedColumn(COL_ALLOW, allowColumnContentWidth());
        if (showTrustColumn) {
            setupFixedColumn(COL_TRUST_AUTHOR, Math.max(ImGui.calcTextSize(COL_TRUST_AUTHOR).x, ImGui.getFrameHeight()));
        }
    }

    private static void setupFixedColumn(String label, float contentW) {
        ImGui.tableSetupColumn(label, ImGuiTableColumnFlags.WidthFixed, contentColumnWidth(contentW));
    }

    private float columnContentWidth(String header, EntryText text) {
        float w = ImGui.calcTextSize(header).x;
        for (JarBatchApprovalProtocol.Entry e : entries) {
            w = Math.max(w, ImGui.calcTextSize(text.apply(e)).x);
        }
        return w;
    }

    private interface EntryText {
        String apply(JarBatchApprovalProtocol.Entry e);
    }

    private static String authorText(JarBatchApprovalProtocol.Entry e) {
        if ("yes".equals(e.zbsValid) && e.zbsSteamId != null) {
            return e.zbsNotice != null && !e.zbsNotice.isEmpty() ? e.zbsNotice : e.zbsSteamId.toString();
        }
        if ("no".equals(e.zbsValid)) {
            return "No";
        }
        if ("unsigned".equals(e.zbsValid)) {
            return "(unsigned)";
        }
        return "?";
    }

    private static String updatedText(JarBatchApprovalProtocol.Entry e) {
        return e.modifiedHuman != null && !e.modifiedHuman.isEmpty() ? e.modifiedHuman : "-";
    }

    private static float steamBanColumnContentWidth() {
        return Math.max(
                ImGui.calcTextSize(COL_STEAM_BAN).x,
                ImGui.calcTextSize("Unknown").x);
    }

    private float allowColumnContentWidth() {
        float w = ImGui.calcTextSize(COL_ALLOW).x;
        w = Math.max(w, ImGui.calcTextSize("Yes (trusted)").x);
        w = Math.max(w, ImGui.calcTextSize("No").x);
        w = Math.max(w, radioButtonWidth("Yes") + ImGui.getStyle().getItemSpacingX() + radioButtonWidth("No"));
        return w;
    }

    static List<JarBatchApprovalProtocol.OutLine> denyAll(List<JarBatchApprovalProtocol.Entry> pending) {
        ArrayList<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(pending.size());
        for (JarBatchApprovalProtocol.Entry e : pending) {
            out.add(new JarBatchApprovalProtocol.OutLine(
                    e.modKey,
                    e.workshopItemId,
                    e.sha256,
                    JarApprovalOutcome.DENY_SESSION.toBatchToken(),
                    null));
        }
        return out;
    }

    private void drawRow(int index, JarBatchApprovalProtocol.Entry e) {
        boolean zbsYes = "yes".equals(e.zbsValid);
        boolean zbsNo = "no".equals(e.zbsValid);
        boolean steamBanYes = "yes".equals(e.steamBanStatus);
        int rowColor = steamBanYes || zbsNo ? ROW_BAD : (zbsYes ? ROW_OK : 0);

        ImGui.tableNextRow(0, TABLE_ROW_MIN_HEIGHT);
        tableRowStartY = ImGui.getCursorPosY();
        if (rowColor != 0) {
            ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, rowColor);
        }

        ImGui.tableSetColumnIndex(0);
        drawMod(e);
        // if (e.jarAbsolutePath != null && !e.jarAbsolutePath.isEmpty()) {
        //     ImGui.textWrapped(e.jarAbsolutePath);
        // }

        ImGui.tableSetColumnIndex(1);
        drawAuthor(e);

        ImGui.tableSetColumnIndex(2);
        cellCenteredText(updatedText(e));

        ImGui.tableSetColumnIndex(3);
        drawSteamBan(e);

        ImGui.tableSetColumnIndex(4);
        drawAllow(index);

        if (showTrustColumn) {
            ImGui.tableSetColumnIndex(5);
            boolean canTrust = zbsYes && !steamBanYes && e.zbsSteamId != null;
            if (canTrust) {
                centerNextItemVertically(ImGui.getFrameHeight());
                centerNextItem(ImGui.getFrameHeight());
                boolean before = trustAuthor[index].get();
                if (clickableCheckbox("##trust-" + index, trustAuthor[index]) && before != trustAuthor[index].get()) {
                    applyTrustAuthor(index, trustAuthor[index].get());
                }
                showTooltipIfHovered(TRUST_AUTHOR_TOOLTIP);
            }
        }
    }

    private void drawMod(JarBatchApprovalProtocol.Entry e) {
        String name = displayName(e);
        String tooltip = modTooltip(e);
        if (e.workshopItemId == null) {
            cellTextWrapped(name);
            showTooltipIfHovered(tooltip);
            return;
        }
        String url = workshopItemUrl(e.workshopItemId.value());
        linkTextWrapped(name, url, "url: " + url + "\n" + tooltip);
    }

    private void drawAuthor(JarBatchApprovalProtocol.Entry e) {
        if ("yes".equals(e.zbsValid) && e.zbsSteamId != null) {
            centerNextItemVertically(ImGui.getTextLineHeight());
            centeredLinkText(authorText(e), authorWorkshopUrl(e.zbsSteamId.value()));
            return;
        }
        if ("no".equals(e.zbsValid)) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, "Invalid signature");
            if (e.zbsNotice != null && !e.zbsNotice.isEmpty()) {
                showTooltipIfHovered(e.zbsNotice); // should be called after text draw
            }
            return;
        }
        if ("unsigned".equals(e.zbsValid)) {
            cellCenteredDisabledText("(unsigned)");
            return;
        }
        cellCenteredText("?");
    }

    private void drawSteamBan(JarBatchApprovalProtocol.Entry e) {
        String status = e.steamBanStatus == null || e.steamBanStatus.isEmpty() ? "unknown" : e.steamBanStatus;
        if ("unknown".equals(status)) {
            cellCenteredTextColored(STEAM_BAN_UNKNOWN, "Unknown");
        } else if ("yes".equals(status)) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, "Yes");
        } else {
            cellCenteredTextColored(STEAM_BAN_NO, "No");
        }
        if (e.steamBanReason != null && !e.steamBanReason.isEmpty()) {
            showTooltipIfHovered(e.steamBanReason);
        }
    }

    private void drawAllow(int index) {
        if (forceDeny[index]) {
            allow[index].set(false);
            cellCenteredText("No");
            return;
        }
        if (trustAuthor[index].get()) {
            allow[index].set(true);
            cellCenteredText("Yes (trusted)");
            return;
        }
        float yesW = radioButtonWidth("Yes");
        float noW = radioButtonWidth("No");
        float rowW = yesW + ImGui.getStyle().getItemSpacingX() + noW;
        centerNextItemVertically(ImGui.getFrameHeight());
        centerNextItem(rowW);
        if (clickableRadioButton("Yes##allow-yes-" + index, allow[index].get())) {
            allow[index].set(true);
        }
        ImGui.sameLine();
        if (clickableRadioButton("No##allow-no-" + index, !allow[index].get())) {
            allow[index].set(false);
        }
    }

    private static boolean clickableButton(String label, float w, float h) {
        boolean clicked = ImGui.button(label, w, h);
        handCursorIfHovered();
        return clicked;
    }

    private static boolean clickableCheckbox(String label, ImBoolean value) {
        boolean clicked = ImGui.checkbox(label, value);
        handCursorIfHovered();
        return clicked;
    }

    private static boolean clickableRadioButton(String label, boolean active) {
        boolean clicked = ImGui.radioButton(label, active);
        handCursorIfHovered();
        return clicked;
    }

    private static void handCursorIfHovered() {
        if (ImGui.isItemHovered()) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        }
    }

    private static void linkTextWrapped(String text, String url) {
        linkTextWrapped(text, url, url);
    }

    private static void linkTextWrapped(String text, String url, String tooltip) {
        centerNextItemVertically(ImGui.getTextLineHeight());
        ImGui.pushStyleColor(ImGuiCol.Text, LINK);
        try {
            ImGui.textWrapped(text);
        } finally {
            ImGui.popStyleColor();
        }
        handleLinkInteraction(url, tooltip);
    }

    private static void centeredLinkText(String text, String url) {
        centerNextItem(ImGui.calcTextSize(text).x);
        ImGui.textColored(LINK, text);
        handleLinkInteraction(url);
    }

    private static void handleLinkInteraction(String url) {
        handleLinkInteraction(url, url);
    }

    private static void handleLinkInteraction(String url, String tooltip) {
        if (!ImGui.isItemHovered()) {
            return;
        }
        ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
        ImGui.setTooltip(tooltip);
        if (ImGui.isItemClicked()) {
            DesktopBrowser.openURL(url);
        }
    }

    private static void showTooltipIfHovered(String text) {
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(text);
        }
    }

    private static String workshopItemUrl(long workshopItemId) {
        return "https://steamcommunity.com/sharedfiles/filedetails/?id=" + workshopItemId;
    }

    private static String modTooltip(JarBatchApprovalProtocol.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.modId != null && !e.modId.isEmpty()) {
            sb.append("id:  ").append(e.modId);
        }
        if (e.jarAbsolutePath != null && !e.jarAbsolutePath.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("jar: ").append(e.jarAbsolutePath);
        }
        return sb.toString();
    }

    private static String authorWorkshopUrl(long steamId) {
        return "https://steamcommunity.com/profiles/" + steamId + "/myworkshopfiles/?appid=" + SteamWorkshop.PZ_APP_ID;
    }

    private static String approvalsFilePath() {
        return Agent.configDir().resolve(ModApprovalsStore.JSON_FILE_NAME).toString();
    }

    private void applyTrustAuthor(int sourceIndex, boolean selected) {
        String key = authorGroupKey[sourceIndex];
        if (key == null || key.isEmpty()) {
            setAllowForTrust(sourceIndex, selected);
            return;
        }
        for (int i = 0; i < authorGroupKey.length; i++) {
            if (key.equals(authorGroupKey[i])) {
                trustAuthor[i].set(selected);
                setAllowForTrust(i, selected);
            }
        }
    }

    private void setAllowForTrust(int index, boolean selected) {
        if (selected) {
            allow[index].set(true);
            return;
        }
        allow[index].set(forceDeny[index] ? false : initialAllow[index]);
    }

    private static float radioButtonWidth(String label) {
        return ImGui.getFrameHeight() + ImGui.getStyle().getItemInnerSpacingX() + ImGui.calcTextSize(label).x;
    }

    private List<JarBatchApprovalProtocol.OutLine> buildLines() {
        ArrayList<JarBatchApprovalProtocol.OutLine> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            boolean rowAllow = allow[i].get();
            JarApprovalOutcome outcome = rowAllow
                    ? (persist.get() ? JarApprovalOutcome.ALLOW_PERSIST : JarApprovalOutcome.ALLOW_SESSION)
                    : (persist.get() ? JarApprovalOutcome.DENY_PERSIST : JarApprovalOutcome.DENY_SESSION);
            if ("no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus)) {
                outcome = persist.get() ? JarApprovalOutcome.DENY_PERSIST : JarApprovalOutcome.DENY_SESSION;
            }
            boolean trust = persist.get() && trustAuthor[i].get() && "yes".equals(e.zbsValid) && e.zbsSteamId != null;
            out.add(new JarBatchApprovalProtocol.OutLine(
                    e.modKey,
                    e.workshopItemId,
                    e.sha256,
                    outcome.toBatchToken(),
                    trust ? e.zbsSteamId : null));
        }
        return out;
    }

    private static boolean initialAllow(JarBatchApprovalProtocol.Entry e) {
        if ("no".equals(e.zbsValid) || "yes".equals(e.steamBanStatus)) {
            return false;
        }
        return PRIOR_YES.equals(e.priorHint);
    }

    private static String displayName(JarBatchApprovalProtocol.Entry e) {
        return e.modDisplayName;
    }

}
