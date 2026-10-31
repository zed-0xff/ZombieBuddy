package me.zed_0xff.zombie_buddy.frontend;

import static me.zed_0xff.zombie_buddy.ModFlags.*;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.SteamWorkshop;
import me.zed_0xff.zombie_buddy.Utils;

import imgui.ImColor;
import imgui.ImDrawList;
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
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static String PRELOAD_NOTICE() { return local.zbselective.i18n.UiText.text("[Preload]", "【预加载】"); }
    private static String PRELOAD_TOOLTIP() { return String.join("\n",
            local.zbselective.i18n.UiText.text("This mod wants ZombieBuddy to load its Java code during agent startup on the next launch.", "此模组希望 ZombieBuddy 在下一次启动时于代理启动阶段加载其 Java 代码。"),
            local.zbselective.i18n.UiText.text("Preloaded mods run before normal Project Zomboid mod loading.", "预加载模组先于常规 Project Zomboid 模组加载。"),
            local.zbselective.i18n.UiText.text("This lets the mod hook earlier game code that is otherwise already loaded.", "这允许模组挂钩更早期的游戏代码（否则这些代码可能已加载）。")); }
    private static final int ROW_OK                     = ImColor.rgba(60, 110, 60, 80);
    private static final int ROW_BAD                    = ImColor.rgba(130, 55, 55, 95);
    private static final int STEAM_BAN_NO               = ImColor.rgb(0, 170, 70);
    private static final int LINK                       = ImColor.rgb(80, 150, 255);
    private static final int ALLOW_CHECK_MARK           = ImColor.rgb(0, 255, 0);
    private static final int DENY_CROSS_MARK            = ImColor.rgb(255, 0, 0);
    private static final int ALERT_TEXT                 = ImColor.rgb(236, 213, 82);

    private static final float BASE_FRAME_HEIGHT        = 19.0f; // vanilla UIFont.Small + 6
    private static final float DIALOG_W                 = 1024.0f;
    private static final float DIALOG_H                 = 600.0f;
    private static final float TABLE_SCROLL_H           = 420.0f;
    private static final float TABLE_ROW_MIN_HEIGHT     = 32.0f;

    private static final int COL_IDX_MOD                = 0;
    private static final int COL_IDX_AUTHOR             = 1;
    private static final int COL_IDX_UPDATED            = 2;
    private static final int COL_IDX_STEAM_BAN          = 3;
    private static final int COL_IDX_ALLOW              = 4;
    private static final int COL_IDX_TRUST_AUTHOR       = 5;

    private static String COL_MOD() { return local.zbselective.i18n.UiText.text("Mod", "模组"); }
    private static String COL_AUTHOR() { return local.zbselective.i18n.UiText.text("Author", "作者"); }
    private static String COL_UPDATED() { return local.zbselective.i18n.UiText.text("Updated", "更新时间"); }
    private static String COL_STEAM_BAN() { return local.zbselective.i18n.UiText.text("Steam ban status", "Steam 封禁状态"); }
    private static String COL_ALLOW() { return local.zbselective.i18n.UiText.text("Allow", "允许"); }
    private static String COL_TRUST_AUTHOR() { return local.zbselective.i18n.UiText.text("Trust author", "信任作者"); }
    private static String TRUST_AUTHOR_TOOLTIP() { return local.zbselective.i18n.UiText.text("Signed mods by that author can be auto-allowed while the signature remains valid and the mod is not banned.", "该作者的已签名模组可在签名仍然有效且未被封禁时自动允许加载。"); }
    private static final String WATERMARK_ICON_RESOURCE = "zb_icon.png";

    private static IconTexture watermarkIcon;
    private static boolean watermarkIconLoadAttempted;

    private final List<JarBatchApprovalProtocol.Entry> entries;
    private final ImBoolean[] allow;
    private final ImBoolean[] trustAuthor;
    private final ImBoolean persistDecisions = new ImBoolean(false);
    private final boolean[] initialAllow;
    private final boolean[] forceDeny;
    private final String[] authorGroupKey;
    private final Set<String> authorsWithBannedMods;
    private final ImBoolean open    = new ImBoolean(true);
    private final AtomicReference<List<JarBatchApprovalProtocol.Entry>> result;
    private static float tableRowStartY;
    private static float tableRowHeight;

    ImguiApprovalDialog(
            List<JarBatchApprovalProtocol.Entry> entries,
            AtomicReference<List<JarBatchApprovalProtocol.Entry>> result) {
        this.entries = entries;
        this.result = result;
        this.allow = new ImBoolean[entries.size()];
        this.trustAuthor = new ImBoolean[entries.size()];
        this.initialAllow = new boolean[entries.size()];
        this.forceDeny = new boolean[entries.size()];
        this.authorGroupKey = new String[entries.size()];

        // Pass 1: build authorGroupKey and compute which authors have any banned mod in the batch.
        Set<String> withBanned = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            this.authorGroupKey[i] = e.zbs.authorSteamId() != null ? e.zbs.authorSteamId().toString() : "";
            if (e.steamBan != null && !this.authorGroupKey[i].isEmpty()) {
                withBanned.add(this.authorGroupKey[i]);
            }
        }
        this.authorsWithBannedMods = withBanned;

        // Pass 2: initialize per-row state using the complete authorsWithBannedMods set.
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            this.initialAllow[i] = initialAllow(e);
            this.forceDeny[i] = forceDeny(e);
            this.allow[i] = new ImBoolean(this.initialAllow[i]);
            this.trustAuthor[i] = new ImBoolean(canTrustAuthor(e) && e.flags.has(MF_TRUST_AUTHOR));
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
        ImGui.setNextWindowSize(dialogWidth(), dialogHeight(), ImGuiCond.Appearing);
        ImGui.setNextWindowPos(
                ImGui.getIO().getDisplaySizeX() * 0.5f,
                ImGui.getIO().getDisplaySizeY() * 0.5f,
                ImGuiCond.Appearing,
                0.5f,
                0.5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowTitleAlign, 0.5f, 0.5f);
        boolean visible = ImGui.begin(local.zbselective.i18n.UiText.text("ZombieBuddy Java Mod Approval", "ZombieBuddy Java 模组审批"), open, ImGuiWindowFlags.NoCollapse);
        ImGui.popStyleVar();
        if (!visible) {
            ImGui.end();
            return;
        }
        drawWindowIconOverlay();
        centeredText(local.zbselective.i18n.UiText.text("Review each Java mod before allowing it to load.", "请在允许加载前逐一审查每个 Java 模组。"));
        ImGui.separator();

        if (ImGui.beginChild("##zb-imgui-approval-scroll", 0.0f, scaled(TABLE_SCROLL_H), true)) {
            int tableFlags = ImGuiTableFlags.Borders
                    | ImGuiTableFlags.RowBg
                    | ImGuiTableFlags.Resizable
                    | ImGuiTableFlags.SizingStretchProp;
            if (ImGui.beginTable("##zb-imgui-approval-table", 6, tableFlags)) {
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
        String forceDialogHint = local.zbselective.i18n.UiText.text("Hold Shift during game load to force-show this dialog", "游戏加载期间按住 Shift 可强制显示此对话框");
        String persistLabel = local.zbselective.i18n.UiText.text("Save decisions to disk (persist across game launches)", "将决定保存到磁盘（跨游戏启动保持）");
        String persistTooltip = local.zbselective.i18n.UiText.text("When disabled, choices apply only to this game launch.", "未勾选时，选择仅对本次游戏启动生效。");
        String cancelLabel = local.zbselective.i18n.UiText.text("Cancel", "取消");
        String okLabel = local.zbselective.i18n.UiText.text("OK", "确定");

        float spacing       = ImGui.getStyle().getItemSpacingX();
        float paddingX      = ImGui.getStyle().getFramePaddingX();
        float checkboxW     = ImGui.getFrameHeight();
        float buttonH       = ImGui.getFrameHeight() * 1.5f;
        float cancelW       = ImGui.calcTextSize(cancelLabel).x + paddingX * 8.0f;
        float okW           = cancelW;
        float persistLabelW = ImGui.calcTextSize(persistLabel).x;
        float persistRowW   = persistLabelW + spacing + checkboxW;
        float buttonRowW    = cancelW + spacing + okW;
        float rightPad      = ImGui.getWindowWidth() - ImGui.getWindowContentRegionMaxX();

        float posY = ImGui.getCursorPosY();
        float leftX = ImGui.getWindowContentRegionMinX();
        float contentBottomY = ImGui.getWindowContentRegionMaxY();
        float textY = Math.max(posY, contentBottomY - ImGui.getTextLineHeight());
        float buttonY = Math.max(posY, contentBottomY - buttonH);
        float persistY = Math.max(posY, buttonY - ImGui.getFrameHeight() - spacing);
        float persistX = Math.max(leftX, ImGui.getWindowWidth() - rightPad - persistRowW);

        ImGui.setCursorPos(leftX, textY);
        ImGui.textDisabled(forceDialogHint);
        showTooltipIfHovered(local.zbselective.i18n.UiText.text("Shows the approval dialog even for previously approved Java mods.", "即使对已批准的 Java 模组也会显示审批对话框。"));

        ImGui.setCursorPos(persistX, persistY);
        ImGui.text(persistLabel);
        showTooltipIfHovered(persistTooltip);
        ImGui.sameLine();
        ImGui.setCursorPosX(persistX + persistLabelW + spacing);
        clickableCheckbox("##persist-decisions", persistDecisions);
        showTooltipIfHovered(persistTooltip);

        ImGui.setCursorPosY(buttonY);
        ImGui.setCursorPosX(Math.max(ImGui.getCursorPosX(), ImGui.getWindowWidth() - rightPad - buttonRowW));
        boolean cancelClicked = clickableButton(cancelLabel, cancelW, buttonH);
        showTooltipIfHovered(local.zbselective.i18n.UiText.text("deny all pending Java mods", "拒绝所有待审批的 Java 模组"));

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

    private static void drawWindowIconOverlay() {
        IconTexture icon = loadWatermarkIcon();
        if (icon == null) {
            return;
        }
        float size = ImGui.getFrameHeight() * 2;
        float pad = ImGui.getStyle().getFramePaddingX();
        float x = ImGui.getWindowPosX() + pad;
        float y = ImGui.getWindowPosY() + pad * 0.5f;

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.pushClipRectFullScreen();
        try {
            drawList.addImage(icon.textureId, x, y, x + size, y + size);
        } finally {
            drawList.popClipRect();
        }
    }

    private void drawHeaderRow() {
        tableRowHeight = scaled(TABLE_ROW_MIN_HEIGHT);
        ImGui.tableNextRow(ImGuiTableRowFlags.Headers, tableRowHeight);
        tableRowStartY = ImGui.getCursorPosY();
        drawHeaderCell(COL_IDX_MOD, COL_MOD());
        drawHeaderCell(COL_IDX_AUTHOR, COL_AUTHOR());
        drawHeaderCell(COL_IDX_UPDATED, COL_UPDATED());
        drawHeaderCell(COL_IDX_STEAM_BAN, COL_STEAM_BAN());
        drawHeaderCell(COL_IDX_ALLOW, COL_ALLOW());
        drawHeaderCell(COL_IDX_TRUST_AUTHOR, COL_TRUST_AUTHOR());
    }

    private static void drawHeaderCell(int columnIndex, String label) {
        tableCell(columnIndex, () -> cellCenteredText(label));
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
        ImGui.setCursorPosY(tableRowStartY + Math.max(0.0f, (tableRowHeight - itemH) * 0.5f));
    }

    private static void tableCell(int columnIndex, Runnable draw) {
        ImGui.tableSetColumnIndex(columnIndex);
        draw.run();
    }

    private static float contentColumnWidth(float contentW) {
        return contentW + ImGui.getStyle().getCellPaddingX() * 2.0f + ImGui.getStyle().getItemSpacingX();
    }

    private static float scaled(float value) {
        return value * (ImGui.getFrameHeight() / BASE_FRAME_HEIGHT);
    }

    private static float dialogWidth() {
        return Math.min(scaled(DIALOG_W), Math.max(320.0f, ImGui.getIO().getDisplaySizeX() - ImGui.getFrameHeight() * 2.0f));
    }

    private static float dialogHeight() {
        return Math.min(scaled(DIALOG_H), Math.max(240.0f, ImGui.getIO().getDisplaySizeY() - ImGui.getFrameHeight() * 2.0f));
    }

    private void setupTableColumns() {
        ImGui.tableSetupColumn(COL_MOD(), ImGuiTableColumnFlags.WidthStretch, 1.0f);
        setupFixedColumn(COL_AUTHOR(), columnContentWidth(COL_AUTHOR(), e -> authorText(e)));
        setupFixedColumn(COL_UPDATED(), columnContentWidth(COL_UPDATED(), e -> updatedText(e)));
        setupFixedColumn(COL_STEAM_BAN(), steamBanColumnContentWidth());
        setupFixedColumn(COL_ALLOW(), allowColumnContentWidth());
        setupFixedColumn(COL_TRUST_AUTHOR(), Math.max(ImGui.calcTextSize(COL_TRUST_AUTHOR()).x, ImGui.getFrameHeight()));
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
        if (e.zbs.valid() && e.zbs.authorSteamId() != null) {
            return !Utils.isBlank(e.zbs.notice()) ? e.zbs.notice() : e.zbs.authorSteamId().toString();
        }
        if (e.zbs.invalid()) {
            return local.zbselective.i18n.UiText.text("No", "否");
        }
        if (e.zbs.unsigned()) {
            return local.zbselective.i18n.UiText.text("(unsigned)", "（未签名）");
        }
        return local.zbselective.i18n.UiText.text("?", "？");
    }

    private static String updatedText(JarBatchApprovalProtocol.Entry e) {
        return formatDate(e.date);
    }

    private static float steamBanColumnContentWidth() {
        return Math.max(
                ImGui.calcTextSize(COL_STEAM_BAN()).x,
                ImGui.calcTextSize(local.zbselective.i18n.UiText.text("Unknown", "未知")).x);
    }

    private float allowColumnContentWidth() {
        float w = ImGui.calcTextSize(COL_ALLOW()).x;
        w = Math.max(w, ImGui.getFrameHeight() * 2.0f + ImGui.getStyle().getItemSpacingX());
        return w;
    }

    private static float rowHeight(JarBatchApprovalProtocol.Entry e) {
        return Math.max(scaled(TABLE_ROW_MIN_HEIGHT), modCellHeight(e));
    }

    private static float modCellHeight(JarBatchApprovalProtocol.Entry e) {
        float height = ImGui.getTextLineHeight();
        if (e.flags.has(MF_PRELOAD)) {
            height += ImGui.getStyle().getItemSpacingY() + ImGui.getTextLineHeight();
        }
        return height;
    }

    static List<JarBatchApprovalProtocol.Entry> denyAll(List<JarBatchApprovalProtocol.Entry> pending) {
        ArrayList<JarBatchApprovalProtocol.Entry> out = new ArrayList<>(pending.size());
        for (JarBatchApprovalProtocol.Entry e : pending) {
            e.decision = false;
            e.flags = e.flags.without(MF_PERSIST | MF_TRUST_AUTHOR);
            out.add(e);
        }
        return out;
    }

    private void drawRow(int index, JarBatchApprovalProtocol.Entry e) {
        boolean zbsYes = e.zbs.valid();
        boolean zbsNo = e.zbs.invalid();
        boolean steamBanYes = e.steamBan != null;
        int rowColor = steamBanYes || zbsNo ? ROW_BAD : (zbsYes ? ROW_OK : 0);

        tableRowHeight = rowHeight(e);
        ImGui.tableNextRow(0, tableRowHeight);
        tableRowStartY = ImGui.getCursorPosY();
        if (rowColor != 0) {
            ImGui.tableSetBgColor(ImGuiTableBgTarget.RowBg0, rowColor);
        }

        tableCell(COL_IDX_MOD, () -> drawMod(e));
        tableCell(COL_IDX_AUTHOR, () -> drawAuthor(e));
        tableCell(COL_IDX_UPDATED, () -> cellCenteredText(updatedText(e)));
        tableCell(COL_IDX_STEAM_BAN, () -> drawSteamBan(e));
        tableCell(COL_IDX_ALLOW, () -> drawAllow(index));
        tableCell(COL_IDX_TRUST_AUTHOR, () -> drawTrustAuthor(index, e));
    }

    private void drawMod(JarBatchApprovalProtocol.Entry e) {
        String tooltip = modTooltip(e);
        if (!e.flags.has(MF_PRELOAD)) {
            drawModTitle(e, tooltip, true);
            return;
        }

        centerNextItemVertically(modCellHeight(e));
        drawModTitle(e, tooltip, false);
        drawPreloadNotice(e);
    }

    private static void drawModTitle(JarBatchApprovalProtocol.Entry e, String tooltip, boolean centerInCell) {
        String name = displayName(e);
        if (e.workshopItemId == null) {
            if (centerInCell) {
                cellTextWrapped(name);
            } else {
                ImGui.textWrapped(name);
            }
            showTooltipIfHovered(tooltip);
            return;
        }

        String url = SteamWorkshop.workshopItemUrl(e.workshopItemId);
        String linkTooltip = local.zbselective.i18n.UiText.text("url: ", "链接: ") + url + "\n" + tooltip;
        if (centerInCell) {
            linkTextWrapped(name, url, linkTooltip);
        } else {
            drawLinkTextWrapped(name, url, linkTooltip);
        }
    }

    private static void drawPreloadNotice(JarBatchApprovalProtocol.Entry e) {
        if (!e.flags.has(MF_PRELOAD)) {
            return;
        }
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.Text, ALERT_TEXT);
        try {
            ImGui.textWrapped(PRELOAD_NOTICE());
        } finally {
            ImGui.popStyleColor();
        }
        showTooltipIfHovered(PRELOAD_TOOLTIP());
    }

    private void drawAuthor(JarBatchApprovalProtocol.Entry e) {
        if (e.zbs.valid() && e.zbs.authorSteamId() != null) {
            centerNextItemVertically(ImGui.getTextLineHeight());
            centeredLinkText(authorText(e), SteamWorkshop.authorWorkshopUrl(e.zbs.authorSteamId()));
            return;
        }
        if (e.zbs.invalid()) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, local.zbselective.i18n.UiText.text("Invalid signature", "签名无效"));
            if (!Utils.isBlank(e.zbs.notice())) {
                showTooltipIfHovered(e.zbs.notice()); // should be called after text draw
            }
            return;
        }
        if (e.zbs.unsigned()) {
            cellCenteredDisabledText(local.zbselective.i18n.UiText.text("(unsigned)", "（未签名）"));
            return;
        }
        cellCenteredText(local.zbselective.i18n.UiText.text("?", "？"));
    }

    private void drawSteamBan(JarBatchApprovalProtocol.Entry e) {
        if (e.steamBan != null) {
            cellCenteredTextColored(1.0f, 0.25f, 0.25f, 1.0f, local.zbselective.i18n.UiText.text("Yes", "是"));
        } else {
            cellCenteredTextColored(STEAM_BAN_NO, local.zbselective.i18n.UiText.text("No", "否"));
        }
        if (e.steamBan != null && !Utils.isBlank(e.steamBan.reason())) {
            showTooltipIfHovered(e.steamBan.reason());
        }
    }

    private void drawAllow(int index) {
        boolean interactive = true;
        if (forceDeny[index]) {
            allow[index].set(false);
            interactive = false;
        } else if (trustAuthor[index].get()) {
            allow[index].set(true);
            interactive = false;
        }
        drawAllowCheckboxes(index, interactive);
    }

    private void drawTrustAuthor(int index, JarBatchApprovalProtocol.Entry e) {
        centerNextItemVertically(ImGui.getFrameHeight());
        centerNextItem(ImGui.getFrameHeight());
        if (canTrustAuthor(e)) {
            boolean before = trustAuthor[index].get();
            if (clickableCheckbox("##trust-" + index, trustAuthor[index]) && before != trustAuthor[index].get()) {
                applyTrustAuthor(index, trustAuthor[index].get());
            }
            showTooltipIfHovered(TRUST_AUTHOR_TOOLTIP());
            return;
        }

        trustAuthor[index].set(false);
        ImGui.beginDisabled(true);
        try {
            ImGui.checkbox("##trust-" + index, trustAuthor[index]);
        } finally {
            ImGui.endDisabled();
        }
        showTooltipIfItemRectHovered(trustAuthorDisabledTooltip(e));
    }

    private void drawAllowCheckboxes(int index, boolean interactive) {
        float checkboxW = ImGui.getFrameHeight();
        float rowW = checkboxW * 2.0f + ImGui.getStyle().getItemSpacingX();
        centerNextItemVertically(ImGui.getFrameHeight());
        centerNextItem(rowW);
        ImGui.beginDisabled(!interactive);
        try {
            if (clickableCheckboxValue("##allow-yes-" + index, allow[index].get(), local.zbselective.i18n.UiText.text("Yes", "是"), ALLOW_CHECK_MARK)) {
                allow[index].set(true);
            }
            ImGui.sameLine();
            if (clickableCrossCheckbox("##allow-no-" + index, !allow[index].get(), local.zbselective.i18n.UiText.text("No", "否"))) {
                allow[index].set(false);
            }
        } finally {
            ImGui.endDisabled();
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

    private static boolean clickableCheckboxValue(String label, boolean checked, String tooltip) {
        return clickableCheckboxValue(label, checked, tooltip, 0);
    }

    private static boolean clickableCheckboxValue(String label, boolean checked, String tooltip, int checkMarkColor) {
        if (checkMarkColor != 0) {
            ImGui.pushStyleColor(ImGuiCol.CheckMark, checkMarkColor);
        }
        try {
            boolean clicked = ImGui.checkbox(label, new ImBoolean(checked));
            handCursorIfHovered();
            showTooltipIfHovered(tooltip);
            return clicked;
        } finally {
            if (checkMarkColor != 0) {
                ImGui.popStyleColor();
            }
        }
    }

    private static boolean clickableCrossCheckbox(String label, boolean checked, String tooltip) {
        if (!checked) {
            return clickableCheckboxValue(label, false, tooltip);
        }

        float size = ImGui.getFrameHeight();
        boolean clicked = ImGui.invisibleButton(label, size, size);
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        float x0 = ImGui.getItemRectMinX();
        float y0 = ImGui.getItemRectMinY();
        float x1 = ImGui.getItemRectMaxX();
        float y1 = ImGui.getItemRectMaxY();
        float rounding = ImGui.getStyle().getFrameRounding();
        int bgColor = ImGui.getColorU32(active
                ? ImGuiCol.FrameBgActive
                : (hovered ? ImGuiCol.FrameBgHovered : ImGuiCol.FrameBg));

        ImDrawList drawList = ImGui.getWindowDrawList();
        drawList.addRectFilled(x0, y0, x1, y1, bgColor, rounding);

        float pad = Math.max(1.0f, (float) (int) (size / 6.0f));
        renderCrossMark(drawList, x0 + pad, y0 + pad, DENY_CROSS_MARK, size - pad * 2.0f);

        if (hovered) {
            ImGui.setMouseCursor(ImGuiMouseCursor.Hand);
            ImGui.setTooltip(tooltip);
        }
        return clicked;
    }

    private static void renderCrossMark(ImDrawList drawList, float x, float y, int color, float size) {
        float thickness = Math.max(size / 5.0f, 1.0f);
        size -= thickness * 0.5f;
        x += thickness * 0.25f;
        y += thickness * 0.25f;

        drawList.pathClear();
        drawList.pathLineTo(x, y);
        drawList.pathLineTo(x + size, y + size);
        drawList.pathStroke(color, 0, thickness);

        drawList.pathClear();
        drawList.pathLineTo(x + size, y);
        drawList.pathLineTo(x, y + size);
        drawList.pathStroke(color, 0, thickness);
    }

    private static IconTexture loadWatermarkIcon() {
        if (watermarkIconLoadAttempted) {
            return watermarkIcon;
        }
        watermarkIconLoadAttempted = true;
        try {
            watermarkIcon = loadIconTexture(WATERMARK_ICON_RESOURCE);
        } catch (Exception e) {
            Logger.warn("Could not load ImGui watermark icon: " + e.getMessage());
            watermarkIcon = null;
        }
        return watermarkIcon;
    }

    private static IconTexture loadIconTexture(String resourceName) throws Exception {
        try (InputStream in = openResource(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("resource not found: " + resourceName);
            }
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IllegalStateException("invalid image: " + resourceName);
            }

            int width = image.getWidth();
            int height = image.getHeight();
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getRGB(x, y);
                    pixels.put((byte) ((argb >> 16) & 0xff));
                    pixels.put((byte) ((argb >> 8) & 0xff));
                    pixels.put((byte) (argb & 0xff));
                    pixels.put((byte) ((argb >> 24) & 0xff));
                }
            }
            pixels.flip();

            int textureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA,
                    width,
                    height,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return new IconTexture(textureId, width, height);
        }
    }

    private static InputStream openResource(String resourceName) {
        ClassLoader cl = ImguiApprovalDialog.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resourceName);
        if (in != null) {
            return in;
        }
        return cl.getResourceAsStream("resources/" + resourceName);
    }

    private static final class IconTexture {
        final int textureId;
        final int width;
        final int height;

        IconTexture(int textureId, int width, int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
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
        drawLinkTextWrapped(text, url, tooltip);
    }

    private static void drawLinkTextWrapped(String text, String url, String tooltip) {
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

    private static void showTooltipIfItemRectHovered(String text) {
        if (Utils.isBlank(text)) {
            return;
        }
        if (ImGui.isMouseHoveringRect(
                ImGui.getItemRectMinX(),
                ImGui.getItemRectMinY(),
                ImGui.getItemRectMaxX(),
                ImGui.getItemRectMaxY())) {
            ImGui.setTooltip(text);
        }
    }

    private static String modTooltip(JarBatchApprovalProtocol.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.flags.has(MF_PRELOAD)) {
            sb.append(PRELOAD_NOTICE());
        }
        appendTooltipLine(sb, local.zbselective.i18n.UiText.text("id:  ", "ID:  "), e.modId);
        appendTooltipLine(sb, local.zbselective.i18n.UiText.text("jar: ", "JAR: "), e.jarAbsolutePath.toString());
        appendTooltipLine(sb, local.zbselective.i18n.UiText.text("inf: ", "mod.info: "), e.infAbsolutePath.toString());
        return sb.toString();
    }

    private static void appendTooltipLine(StringBuilder sb, String label, String value) {
        if (Utils.isBlank(value)) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(label).append(value);
    }

    private void applyTrustAuthor(int sourceIndex, boolean selected) {
        String key = authorGroupKey[sourceIndex];
        if (Utils.isBlank(key)) {
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

    private List<JarBatchApprovalProtocol.Entry> buildLines() {
        ArrayList<JarBatchApprovalProtocol.Entry> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            JarBatchApprovalProtocol.Entry e = entries.get(i);
            boolean rowAllow = allow[i].get();
            if (forceDeny(e)) {
                rowAllow = false;
            }
            e.decision = rowAllow;
            if (persistDecisions.get()) {
                e.flags = e.flags.with(MF_PERSIST);
            } else {
                e.flags = e.flags.without(MF_PERSIST);
            }
            if (persistDecisions.get() && trustAuthor[i].get() && canTrustAuthor(e)) {
                e.flags = e.flags.with(MF_TRUST_AUTHOR);
            } else {
                e.flags = e.flags.without(MF_TRUST_AUTHOR);
            }
            out.add(e);
        }
        return out;
    }

    private static boolean initialAllow(JarBatchApprovalProtocol.Entry e) {
        if (forceDeny(e)) {
            return false;
        }
        return Boolean.TRUE.equals(e.decision);
    }

    private static boolean forceDeny(JarBatchApprovalProtocol.Entry e) {
        return e.zbs.invalid() || e.steamBan != null;
    }

    private boolean canTrustAuthor(JarBatchApprovalProtocol.Entry e) {
        if (!e.zbs.valid() || e.steamBan != null || e.zbs.authorSteamId() == null) {
            return false;
        }
        return !authorsWithBannedMods.contains(e.zbs.authorSteamId().toString());
    }

    private String trustAuthorDisabledTooltip(JarBatchApprovalProtocol.Entry e) {
        if (e.steamBan != null) {
            return local.zbselective.i18n.UiText.text("Cannot trust author: this mod has a Steam ban.", "无法信任该作者：此模组已被 Steam 封禁。");
        }
        if (e.zbs.authorSteamId() != null && authorsWithBannedMods.contains(e.zbs.authorSteamId().toString())) {
            return local.zbselective.i18n.UiText.text("Cannot trust author: they have a banned mod in this batch.", "无法信任该作者：其在本批次中有被封禁的模组。");
        }
        if (e.zbs.invalid()) {
            return local.zbselective.i18n.UiText.text("Cannot trust author: signature is invalid.", "无法信任该作者：签名无效。");
        }
        if (e.zbs.unsigned()) {
            return local.zbselective.i18n.UiText.text("Cannot trust author: mod is unsigned.", "无法信任该作者：模组未签名。");
        }
        if (e.zbs.authorSteamId() == null) {
            return local.zbselective.i18n.UiText.text("Cannot trust author: signature has no author SteamID.", "无法信任该作者：签名中缺少作者 SteamID。");
        }
        return "";
    }

    private static String formatDate(Date date) {
        if (date == null) {
            return "-";
        }
        return new SimpleDateFormat(DATE_FORMAT, Locale.ROOT).format(date);
    }

    private static String displayName(JarBatchApprovalProtocol.Entry e) {
        return e.modDisplayName;
    }

}
