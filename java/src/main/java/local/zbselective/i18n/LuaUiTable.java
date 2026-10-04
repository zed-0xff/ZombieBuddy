package local.zbselective.i18n;
import java.util.Map;
final class LuaUiTable {
    static final Map<String,String> CN = Map.ofEntries(
        Map.entry("UI_ZB_Install_Windows","<H1> ZombieBuddy 尚未安装！ <LINE> <LINE> <H2> 自动安装（推荐）： <LINE> <TEXT> 1. 从以下地址下载 <H2>ZombieBuddyInstaller.exe<TEXT>： <LINE> <INDENT:40><H2> %1 <INDENT:0><LINE> <TEXT> 2. 运行安装程序并重启 Steam。 <LINE> <LINE> <H2> 手动安装（备用）： <LINE> <TEXT> 将文件从 <H2>%2<TEXT> 复制到 <H2>%3<TEXT>，并在 Steam 启动选项中添加 <H2>%4<TEXT>。 <LINE> <LINE> <IMAGECENTRE:SCREENSHOT_PLACEHOLDER>"),
        Map.entry("UI_ZB_Install_Unix","<H1> ZombieBuddy 需要手动安装。 <LINE> <LINE> <H2> 请复制以下文件： <LINE> <INDENT:40><TEXT> %4 <LINE><INDENT:0><H2> 从 <LINE> <INDENT:40><TEXT> %1 <BR><INDENT:0><H2> 到 <LINE> <INDENT:40><TEXT> %2 <BR><INDENT:0><LINE> <H2> 然后在 Steam 启动选项中添加： <LINE><INDENT:40><TEXT> %3 <BR><INDENT:0><LINE> <TEXT><LINE> <IMAGECENTRE:SCREENSHOT_PLACEHOLDER>"),
        Map.entry("UI_ZB_AutoFixModOrder","启动时自动修复模组加载顺序"),
        Map.entry("UI_ZB_FixApprovalDialogCursor","修复审批对话框光标"),
        Map.entry("UI_ZB_SuppressSandboxLog","抑制沙盒选项日志"),
        Map.entry("UI_ZB_WatermarkOpacity","水印不透明度"),
        Map.entry("UI_ZB_AutoFixModOrder_desc","将 zdk 与 ZModUnbork（如存在）移至模组顺序顶部，防止损坏或已弃用的模组导致游戏无法启动"),
        Map.entry("UI_ZB_FixApprovalDialogCursor_desc","在 Java 模组审批对话框打开时恢复可见鼠标光标；修复光标消失与点击无响应"),
        Map.entry("UI_ZB_SuppressSandboxLog_desc","隐藏游戏启动时对所有沙盒变量的默认日志输出")
    );
    static final Map<String,String> EN = Map.ofEntries(
        Map.entry("UI_ZB_Install_Windows","<H1> ZombieBuddy is not installed! <LINE> <LINE> <H2> Automated Installation (Recommended): <LINE> <TEXT> 1. Download <H2>ZombieBuddyInstaller.exe<TEXT> from: <LINE> <INDENT:40><H2> %1 <INDENT:0><LINE> <TEXT> 2. Run the installer and restart Steam. <LINE> <LINE> <H2> Manual Installation (Fallback): <LINE> <TEXT> Copy files from <H2>%2<TEXT> to <H2>%3<TEXT> and add <H2>%4<TEXT> to Steam launch options. <LINE> <LINE> <IMAGECENTRE:SCREENSHOT_PLACEHOLDER>"),
        Map.entry("UI_ZB_Install_Unix","<H1> ZombieBuddy requires manual installation. <LINE> <LINE> <H2> Please copy the following files: <LINE> <INDENT:40><TEXT> %4 <LINE><INDENT:0><H2> from <LINE> <INDENT:40><TEXT> %1 <BR><INDENT:0><H2> to <LINE> <INDENT:40><TEXT> %2 <BR><INDENT:0><LINE> <H2> Then add to Steam launch options: <LINE><INDENT:40><TEXT> %3 <BR><INDENT:0><LINE> <TEXT><LINE> <IMAGECENTRE:SCREENSHOT_PLACEHOLDER>"),
        Map.entry("UI_ZB_AutoFixModOrder","Automatically fix mod order on launch"),
        Map.entry("UI_ZB_FixApprovalDialogCursor","Fix cursor in mod approval dialog"),
        Map.entry("UI_ZB_SuppressSandboxLog","Suppress sandbox options logging"),
        Map.entry("UI_ZB_WatermarkOpacity","Watermark Opacity"),
        Map.entry("UI_ZB_AutoFixModOrder_desc","Moves zdk and ZModUnbork (if present) to the top of the mod order to prevent borken or deprecated mods from making the game unbootable"),
        Map.entry("UI_ZB_FixApprovalDialogCursor_desc","Restores the visible mouse cursor while the Java mod approval dialog is open; fixes invisible cursor and unresponsive clicks"),
        Map.entry("UI_ZB_SuppressSandboxLog_desc","Hides the vanilla startup log dump of all sandbox variables")
    );
}
