// package me.zed_0xff.zombie_buddy.frontend;
// 
// import java.util.ArrayList;
// import java.util.List;
// import java.util.concurrent.atomic.AtomicReference;
// 
// import me.zed_0xff.XUI.Color;
// import me.zed_0xff.XUI.HostCursorProvider;
// import me.zed_0xff.XUI.IngameButton;
// import me.zed_0xff.XUI.IngameWindow;
// import me.zed_0xff.XUI.Label;
// import me.zed_0xff.XUI.Session;
// import me.zed_0xff.XUI.Window;
// import me.zed_0xff.zombie_buddy.JarApprovalOutcome;
// import me.zed_0xff.zombie_buddy.JarBatchApprovalProtocol;
// import me.zed_0xff.zombie_buddy.JarDecisionTable;
// import me.zed_0xff.zombie_buddy.Loader;
// import me.zed_0xff.zombie_buddy.Logger;
// import org.lwjgl.opengl.GL11;
// import org.lwjgl.opengl.GL13;
// import org.lwjgl.opengl.GL20;
// import org.lwjglx.opengl.Display;
// import zombie.core.SpriteRenderer;
// import zombie.core.opengl.RenderThread;
// import zombie.core.textures.Texture;
// 
// /**
//  * In-game XUI approval dialog driven on PZ's OpenGL context thread.
//  *
//  * <p>Mod approvals happen while the game is still loading, before the normal
//  * UI frame loop is running, so this renders synchronously via
//  * {@link RenderThread#invokeOnRenderContext(Runnable)}.
//  */
// public final class XUIModApprovalFrontend implements ModApprovalFrontend {
// 
//     private static final int UI_SCALE = 2;
//     private static final int PAGE_SIZE = 8;
//     private static final Color TEXT = new Color(0xe6e0d4);
//     private static final Color MUTED_TEXT = new Color(0xa8a8a8);
//     private static final JarApprovalOutcome[] OUTCOME_CYCLE = {
//         JarApprovalOutcome.DENY_SESSION,
//         JarApprovalOutcome.ALLOW_SESSION,
//         JarApprovalOutcome.ALLOW_PERSIST,
//         JarApprovalOutcome.DENY_PERSIST
//     };
// 
//     @Override
//     public void approvePendingMods(List<JarBatchApprovalProtocol.Entry> pending, JarDecisionTable disk) {
//         if (pending.isEmpty()) {
//             return;
//         }
// 
//         JarApprovalOutcome[] outcomes = showDialog(pending);
//         List<JarBatchApprovalProtocol.OutLine> lines = new ArrayList<>(pending.size());
//         for (int i = 0; i < pending.size(); i++) {
//             JarBatchApprovalProtocol.Entry e = pending.get(i);
//             JarApprovalOutcome outcome = outcomes[i] != null ? outcomes[i] : JarApprovalOutcome.DENY_SESSION;
//             lines.add(new JarBatchApprovalProtocol.OutLine(
//                     e.modKey,
//                     e.workshopItemId,
//                     e.sha256,
//                     outcome.toBatchToken(),
//                     null
//             ));
//         }
//         Loader.applyBatchApprovalLines(lines, disk);
//     }
// 
//     private JarApprovalOutcome[] showDialog(List<JarBatchApprovalProtocol.Entry> pending) {
//         long win = getGameWindow();
//         if (win == 0) {
//             Logger.error("XUIModApprovalFrontend: no game window");
//             return denyAll(pending.size());
//         }
// 
//         JarApprovalOutcome[] outcomes = new JarApprovalOutcome[pending.size()];
//         for (int i = 0; i < outcomes.length; i++) {
//             outcomes[i] = initialOutcome(pending.get(i));
//         }
// 
//         AtomicReference<JarApprovalOutcome[]> result = new AtomicReference<>();
//         Session[] sessionRef = { null };
//         sessionRef[0] = new Session(win, () -> buildWindow(pending, outcomes, result, sessionRef));
// 
//         long deadline = Loader.g_batchApprovalTimeoutSeconds > 0
//                 ? System.currentTimeMillis() + Loader.g_batchApprovalTimeoutSeconds * 1000L
//                 : Long.MAX_VALUE;
// 
//         while (result.get() == null && !sessionRef[0].isDone()) {
//             if (System.currentTimeMillis() >= deadline) {
//                 Logger.warn("XUI approval timed out; denying pending mods for session");
//                 result.compareAndSet(null, denyAll(pending.size()));
//                 sessionRef[0].dismiss();
//                 break;
//             }
// 
//             int fbW = Display.getWidth();
//             int fbH = Display.getHeight();
// 
//             try {
//                 RenderThread.invokeOnRenderContext(() -> {
//                     Display.processMessages();
//                     int rawMouseX = org.lwjglx.input.Mouse.getX();
//                     int rawMouseY = fbH - org.lwjglx.input.Mouse.getY() - 1;
//                     int mouseX = rawMouseX / UI_SCALE;
//                     int mouseY = rawMouseY / UI_SCALE;
//                     boolean leftDown = org.lwjglx.input.Mouse.isButtonDown(0);
//                     int cursor = renderSession(sessionRef[0], fbW, fbH, mouseX, mouseY, leftDown);
//                     applyCursor(cursor);
//                     Display.update(false);
//                 });
//             } catch (Throwable t) {
//                 Logger.error("XUI approval render failed: " + t);
//                 result.compareAndSet(null, denyAll(pending.size()));
//                 sessionRef[0].dismiss();
//                 break;
//             }
// 
//             sleepQuietly(16);
//         }
// 
//         JarApprovalOutcome[] selected = result.get();
//         return selected != null ? selected : denyAll(pending.size());
//     }
// 
//     private static Window buildWindow(
//             List<JarBatchApprovalProtocol.Entry> pending,
//             JarApprovalOutcome[] outcomes,
//             AtomicReference<JarApprovalOutcome[]> result,
//             Session[] sessionRef) {
//         Window w = new IngameWindow(0, 0, 940, 170 + PAGE_SIZE * 42, "ZombieBuddy - Java Mod Approval");
//         BatchDialogState state = new BatchDialogState(pending, outcomes);
// 
//         w.addControl(win -> {
//             state.summary = label(win, 0, 0, 760, 20, "", TEXT);
//             return state.summary;
//         });
// 
//         for (int row = 0; row < PAGE_SIZE; row++) {
//             final int rowIndex = row;
//             int y = 34 + row * 42;
//             w.addControl(win -> {
//                 Label l = label(win, 0, y, 650, 20, "", TEXT);
//                 state.modLabels[rowIndex] = l;
//                 return l;
//             });
//             w.addControl(win -> {
//                 Label l = label(win, 0, y + 18, 650, 20, "", MUTED_TEXT);
//                 state.detailLabels[rowIndex] = l;
//                 return l;
//             });
//             w.addControl(win -> {
//                 IngameButton b = new IngameButton(win, 690, y + 4, 165, 28, "") {
//                     @Override protected void onClick() {
//                         int index = state.entryIndex(rowIndex);
//                         if (index < pending.size()) {
//                             outcomes[index] = nextOutcome(outcomes[index]);
//                             state.refresh();
//                         }
//                     }
//                 };
//                 state.decisionButtons[rowIndex] = b;
//                 return b;
//             });
//         }
// 
//         int footerY = 44 + PAGE_SIZE * 42;
//         w.addControl(win -> new IngameButton(win, 0, footerY, 105, 28, "Prev") {
//             @Override protected void onClick() {
//                 state.prevPage();
//             }
//         });
//         w.addControl(win -> new IngameButton(win, 115, footerY, 105, 28, "Next") {
//             @Override protected void onClick() {
//                 state.nextPage();
//             }
//         });
//         w.addControl(win -> new IngameButton(win, 245, footerY, 145, 28, "Allow all") {
//             @Override protected void onClick() {
//                 fill(outcomes, JarApprovalOutcome.ALLOW_SESSION);
//                 state.refresh();
//             }
//         });
//         w.addControl(win -> new IngameButton(win, 400, footerY, 145, 28, "Deny all") {
//             @Override protected void onClick() {
//                 fill(outcomes, JarApprovalOutcome.DENY_SESSION);
//                 state.refresh();
//             }
//         });
//         w.addControl(win -> new IngameButton(win, 710, footerY, 145, 28, "Apply") {
//             @Override protected void onClick() {
//                 result.compareAndSet(null, outcomes.clone());
//                 sessionRef[0].dismiss();
//             }
//         });
// 
//         state.refresh();
//         return w;
//     }
// 
//     private static int renderSession(Session session, int fbW, int fbH, int mouseX, int mouseY, boolean leftDown) {
//         int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
//         int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
//         int cursor = Window.HOST_CURSOR_DEFAULT;
//         GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
//         try {
//             prepareFixedFunctionGl();
//             cursor = session.runRenderOnlyWithHostInput(fbW, fbH, UI_SCALE, mouseX, mouseY, leftDown);
//         } finally {
//             GL11.glPopAttrib();
//             GL20.glUseProgram(previousProgram);
//             GL13.glActiveTexture(previousActiveTexture);
//             Texture.lastTextureID = -1;
//             SpriteRenderer.ringBuffer.restoreBoundTextures = true;
//             SpriteRenderer.ringBuffer.restoreVbos = true;
//         }
//         return cursor;
//     }
// 
//     private static void prepareFixedFunctionGl() {
//         GL20.glUseProgram(0);
//         GL13.glActiveTexture(GL13.GL_TEXTURE1);
//         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
//         GL11.glDisable(GL11.GL_TEXTURE_2D);
//         GL13.glActiveTexture(GL13.GL_TEXTURE0);
//         GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
//     }
// 
//     private static void applyCursor(int cursor) {
//         org.lwjgl.glfw.GLFW.glfwSetCursor(Display.getWindow(), HostCursorProvider.handleFor(cursor));
//     }
// 
//     private static long getGameWindow() {
//         if (!Display.isCreated()) {
//             return 0;
//         }
//         return Display.getWindow();
//     }
// 
//     private static void sleepQuietly(long millis) {
//         try {
//             Thread.sleep(millis);
//         } catch (InterruptedException ex) {
//             Thread.currentThread().interrupt();
//         }
//     }
// 
//     private static Label label(Window window, int x, int y, int w, int h, String text, Color color) {
//         Label label = new Label(window, x, y, w, h, text);
//         label.setTextColor(color);
//         return label;
//     }
// 
//     private static String label(JarBatchApprovalProtocol.Entry e) {
//         if (e.modDisplayName != null && !e.modDisplayName.isEmpty()) {
//             return e.modDisplayName + " (" + e.modKey + ")";
//         }
//         return e.modKey;
//     }
// 
//     private static String zbsLine(JarBatchApprovalProtocol.Entry e) {
//         String status = e.zbsValid == null || e.zbsValid.isEmpty() ? "unknown" : e.zbsValid;
//         if (e.zbsNotice != null && !e.zbsNotice.isEmpty()) {
//             return status + " - " + e.zbsNotice;
//         }
//         return status;
//     }
// 
//     private static String steamBanLine(JarBatchApprovalProtocol.Entry e) {
//         String status = e.steamBanStatus == null || e.steamBanStatus.isEmpty() ? "unknown" : e.steamBanStatus;
//         if (e.steamBanReason != null && !e.steamBanReason.isEmpty()) {
//             return status + " - " + e.steamBanReason;
//         }
//         return status;
//     }
// 
//     private static String shortSha(String sha) {
//         if (sha == null) {
//             return "";
//         }
//         return sha.substring(0, Math.min(48, sha.length()));
//     }
// 
//     private static JarApprovalOutcome initialOutcome(JarBatchApprovalProtocol.Entry e) {
//         return "yes".equalsIgnoreCase(e.priorHint)
//                 ? JarApprovalOutcome.ALLOW_SESSION
//                 : JarApprovalOutcome.DENY_SESSION;
//     }
// 
//     private static JarApprovalOutcome nextOutcome(JarApprovalOutcome current) {
//         for (int i = 0; i < OUTCOME_CYCLE.length; i++) {
//             if (OUTCOME_CYCLE[i] == current) {
//                 return OUTCOME_CYCLE[(i + 1) % OUTCOME_CYCLE.length];
//             }
//         }
//         return OUTCOME_CYCLE[0];
//     }
// 
//     private static JarApprovalOutcome[] denyAll(int count) {
//         JarApprovalOutcome[] outcomes = new JarApprovalOutcome[count];
//         fill(outcomes, JarApprovalOutcome.DENY_SESSION);
//         return outcomes;
//     }
// 
//     private static void fill(JarApprovalOutcome[] outcomes, JarApprovalOutcome outcome) {
//         for (int i = 0; i < outcomes.length; i++) {
//             outcomes[i] = outcome;
//         }
//     }
// 
//     private static String outcomeLabel(JarApprovalOutcome outcome) {
//         switch (outcome) {
//             case ALLOW_SESSION: return "Allow session";
//             case ALLOW_PERSIST: return "Allow save";
//             case DENY_PERSIST: return "Deny save";
//             case DENY_SESSION:
//             default: return "Deny session";
//         }
//     }
// 
//     private static final class BatchDialogState {
//         private final List<JarBatchApprovalProtocol.Entry> pending;
//         private final JarApprovalOutcome[] outcomes;
//         private final Label[] modLabels = new Label[PAGE_SIZE];
//         private final Label[] detailLabels = new Label[PAGE_SIZE];
//         private final IngameButton[] decisionButtons = new IngameButton[PAGE_SIZE];
//         private Label summary;
//         private int page;
// 
//         private BatchDialogState(List<JarBatchApprovalProtocol.Entry> pending, JarApprovalOutcome[] outcomes) {
//             this.pending = pending;
//             this.outcomes = outcomes;
//         }
// 
//         private int entryIndex(int row) {
//             return page * PAGE_SIZE + row;
//         }
// 
//         private void prevPage() {
//             if (page > 0) {
//                 page--;
//                 refresh();
//             }
//         }
// 
//         private void nextPage() {
//             if ((page + 1) * PAGE_SIZE < pending.size()) {
//                 page++;
//                 refresh();
//             }
//         }
// 
//         private void refresh() {
//             int pages = Math.max(1, (pending.size() + PAGE_SIZE - 1) / PAGE_SIZE);
//             summary.setText("Pending Java mods: " + pending.size() + " (page " + (page + 1) + "/" + pages + ")");
//             for (int row = 0; row < PAGE_SIZE; row++) {
//                 int index = entryIndex(row);
//                 if (index < pending.size()) {
//                     JarBatchApprovalProtocol.Entry e = pending.get(index);
//                     modLabels[row].visible = true;
//                     detailLabels[row].visible = true;
//                     decisionButtons[row].visible = true;
//                     modLabels[row].setText((index + 1) + ". " + label(e));
//                     detailLabels[row].setText("ZBS: " + zbsLine(e)
//                             + " | Steam: " + steamBanLine(e)
//                             + " | SHA: " + shortSha(e.sha256));
//                     decisionButtons[row].enabled = true;
//                     decisionButtons[row].setText(outcomeLabel(outcomes[index]));
//                 } else {
//                     modLabels[row].visible = false;
//                     detailLabels[row].visible = false;
//                     decisionButtons[row].visible = false;
//                     modLabels[row].setText("");
//                     detailLabels[row].setText("");
//                     decisionButtons[row].enabled = false;
//                     decisionButtons[row].setText("");
//                 }
//             }
//         }
//     }
// }
