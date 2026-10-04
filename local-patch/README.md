# ZombieBuddy 2.3.3 — LY Optimized 1.1.2

基于官方稳定版 **v2.3.3 / commit `0ddf161c27848f12d09e74de7fadbea9d50e621d`** 的源码修复与优化版本。已使用本机 **Project Zomboid 42.21 / 游戏自带 Java 25** 验证。GitHub master 是独立的 3.x 重构分支，本包不引入该分支的大规模重构。

**这是原作者尚未发布新版修复期间制作的临时修复与性能优化包。原作者发布更新后，本创意工坊 Mod 将删除；届时请回到原作者的官方版本。**

Fork 仓库：[yuruichang/ZombieBuddy](https://github.com/yuruichang/ZombieBuddy)；本次源码分支：[b42-compatibility-performance-localization](https://github.com/yuruichang/ZombieBuddy/tree/b42-compatibility-performance-localization)。

## 新版故障的原因

42.21 的 `ZomboidFileSystem.loadMods` 参数是 `List<String>`。原框架的加载 Advice 声明为 `ArrayList<String>`，无法匹配目标方法，所以能看到框架启动，却没有 Java 模组加载。本包将 Advice 和内部加载逻辑改为 `List`，同时保留 `Loader.loadMods(ArrayList)` 的旧入口，兼容已编译的旧调用者。

依据：[上游 Issue #53](https://github.com/zed-0xff/ZombieBuddy/issues/53)、[未合并 PR #56](https://github.com/zed-0xff/ZombieBuddy/pull/56)。另对比了 [工坊临时修复 3807686870](https://steamcommunity.com/sharedfiles/filedetails/?id=3807686870) 的实际 JAR，确认其加载器修复方向相同；本包由官方稳定版源码构建，没有直接改用他人的二进制。

## 内置自动汉化（1.1.0）

之前的 ZombieBuddyCN 译文、原 Lua 设置界面翻译及 Noto Sans SC 中文字库已直接编入本 JAR。**无需订阅或手动启用 ZombieBuddyCN，也没有额外的汉化审批或前置 Agent。**

- 游戏语言为简体中文 `CN` 时显示中文；英文及其他语言使用原英文。
- 首次加载游戏语言前读取 `options.ini`，支持 `-cachedir`；游戏自己的语言设置生效后跟随 `Translator`，切换 CN/EN 时更新标签、水印与设置文本。
- 覆盖 ImGui/Swing/TinyFD/控制台审批文字、签名说明、加载提示、水印、原模组设置和重启提示。Swing 子进程继承父游戏语言。
- 审批的 Boolean 值、y/n 输入、模组 ID、哈希、签名和 JSON 协议字段不翻译。
- 如果旧 ZombieBuddyCN 仍在启用/预加载列表中，自动跳过它的 `cn.zbcn` Java 入口，避免旧的固定中文转换器覆盖内置动态语言。
- TinyFD 的系统原生按钮文字由操作系统提供；标题、正文和框架自绘界面按游戏语言显示。JAR 没有启动时，自然无法运行其内置翻译。

原汉化提供的 Noto Sans SC 字库采用 SIL OFL 1.1，许可保存在 JAR 的 `fonts/OFL.txt`。中文字体仅在审批窗口创建字体图集时合并；未新增后台语言轮询或全游戏字符串拦截。

## 性能与兼容性改动

- 扫描每个 Java 模组时，只扫描当前 JAR，减少重复扫描全部已加载模组的工作；保留 ClassGraph、LuaClass 和全局 LuaMethod 处理。
- 每个补丁转换器先按目标类快速过滤，再进入 ByteBuddy。精确名称、前后缀、通配符和正则目标保留原含义。回调中使用普通循环，避免惰性 Stream 类加载触发循环加载错误。
- ByteBuddy 安装时已重新转换已加载的目标类，移除之后的第二次重复转换；保留必要的首次转换和 warmUp 类初始化。
- 合并之前独立优化补丁的重启约束。新获准、更新或本次运行重新启用的 Java 模组先记录、不执行；实际游戏选择的审批批次结束后提示重启并退出。已加载模组在下一次游戏选择中被停用或拒绝，也要求重启以清除旧 hook；返回默认菜单不触发这条比较。
- 停用或尚未准备的预加载条目，在读取和校验 JAR 前跳过，避免无用预加载。不同存档与默认列表的衔接保留待加载计划，避免互相覆盖造成重启循环。
- 三处 HTTP 客户端改为按需初始化、共享实例。初始化失败进入原网络失败回退，不再因静态初始化异常让整个游戏无法启动。不更改签名、公钥、封禁或允许/拒绝判定。
- Windows `patches_jar` 按最后一个冒号切分包名，避免把盘符冒号误当作分隔符。
- 包含官方 v2.3.3 的字体 `isLoading` / `isEmpty` 兼容修复，避免旧版实验日志界面的相应方法缺失错误。

这减少无关处理，并不意味着删掉框架全部 hook。框架必要目标仍保留；模组主动声明的广泛通配目标不会被擅自缩小。

## 替换方式

原 ZombieBuddy 必须已安装和配置；保留其工坊订阅及启用状态。**完全关闭游戏、Coop 与服务器**，备份实际游戏目录中的 `ZombieBuddy.jar`，然后用本包同名 JAR 替换。**不要覆盖工坊原 ZombieBuddy 的 JAR 或其他模组 JAR。**

可以运行带备份和文件校验的替换脚本：

```powershell
.\Replace-Jar.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid'
```

脚本保留原启动方式、`zbNative.dll` 和其他参数；如 JSON 中曾启用先前的 `ZombieBuddySelectiveHooks.jar`，移除它的启动参数，因为这版已将功能合并。旧 Agent 如在 Steam 或 BAT 中手工配置，也要移除。两版不能同时加载。

### 作者签名列表也必须替换

本包包含从官方 GitHub master `a403dafae4e8a37e1ccefda1936927782709f82d` 原样取得的 `authors.json`，`updated_at=2026-08-16`，含 10 位作者，已通过官方 Ed25519 公钥验签。工坊原文件仍是 2026-05-01 的 1 位作者版本。

关闭游戏后，把本包 `authors.json` **原样复制到两处**：

1. 原 ZombieBuddy 工坊模组根目录，例如 `E:\Steam\steamapps\workshop\content\108600\3619862853\mods\ZombieBuddy\authors.json`（与 `libs`、`41`、`42` 同级）。
2. 实际运行作者缓存：默认 `%USERPROFILE%\.zombie_buddy\authors.json`。若使用 `config_dir` 启动参数，则替换对应配置目录中的 `authors.json`。

当前框架联网时会重新获取并验签；网络不可用时实际使用的是第二处缓存。因此，两处都更新，才能覆盖原模组文件和离线缓存。不要重排 JSON、编辑名单或改变文件编码/换行，签名覆盖原始内容。

替换脚本会定位同 Steam 库内的工坊目录，备份并更新上述两处文件。不同库或自定义配置目录可明确指定：

```powershell
.\Replace-Jar.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid' -ZombieBuddyModDir 'E:\Steam\steamapps\workshop\content\108600\3619862853\mods\ZombieBuddy' -ConfigDir "$env:USERPROFILE\.zombie_buddy"
```

原 JAR、原签名边车、已有 `ZombieBuddy.jar.new` 和启动 JSON 会保存到脚本输出的备份目录。替换时清除已备份的旧签名边车及待替换 `.new`，以免旧签名误用于新文件、或下次启动立即覆盖本包。

首次运行时照常通过 ZombieBuddy 的原审批窗口批准 Java 模组。建议保存决定。批准后出现中英文“必须重启”窗口，确认后完整退出，重新启动后生效。仅本次允许的决定可能在下次启动再次询问；待加载计划不会替代授权。

## 回退与官方更新

关闭游戏，把备份的原 `ZombieBuddy.jar` 放回。若安装脚本曾移除旧 Agent 参数，而你要恢复之前的独立 Agent 方案，再恢复备份 JSON。原版不会使用本包的待加载文件。

作者文件分别备份为 `authors-workshop.json`、`authors-cache.json`，原路径记录在 `authors-paths.json`。需要回退时按该记录原样恢复。

JAR 的数字版本保留 **2.3.3**，额外清单字段 `X-Local-Optimized` 和显示后缀标识本地优化版。没有伪装成官方签名版本，也没有抬高版本号阻止官方更新。未来官方更高版本仍可通过原更新器替换本包；替换后本包优化不再生效。

本包是未经原作者签名的修改版，内部旧 JAR 签名已移除；其他 Java 模组的 ZBS 校验、原信任名单和审批政策保持原样。

## 验证与边界

上游 **142 例**全部通过：97 单元测试、30 真实补丁测试、15 原版对照。一个原测试依赖作者个人 Workshop 目录，改为临时目录内的真实 `workshop.txt` 夹具，未改变生产路径识别逻辑。

另有 **29 个性能/兼容性 JVM 流程**通过，以及 6 组自动语言/字体验证（CN/EN 启动与实时切换、CN/EN 子进程、带空格的自定义缓存路径、原生 ImGui 汉字/Latin 字形）。审批决策与无效签名拒绝在两种语言下均已验证。新增回归覆盖从默认/存档启动计划反复返回不同菜单列表、不执行菜单新增 Java、不覆盖存档缓存、重新进入同一存档，以及实际游戏新增/移除 Java 仍要求重启。原有专项包括：新模组延迟执行、重启后 Advice/MethodDelegation、已加载类只转换一次、正则目标、Lua API 暴露、更新与重新启用、停用退出、存档列表衔接、42.21 真实加载入口、惰性 HTTP、初始化失败回退以及原 `zbNative.dll` 启动。JVM 验证启用 `-Xverify:all`。

没有进入完整游戏世界或进行全部模组组合和多人实机验收；未测量完整启动耗时，不承诺提速百分比。本包修复框架入口，不能自动修复其他 Java 模组对新版游戏 API 的不兼容。

缓存位于 ZombieBuddy 配置目录的 `selective-hooks.properties`；不改原审批文件。自定义游戏 `-cachedir` 时，可增加 `-Dzbselective.defaultMods=实际缓存目录/mods/default.txt`，保证启动前的预加载启用判断读取正确清单。可用 `-Dzbselective.state=完整路径` 独立指定待加载文件。无界面服务器用 `-Dzbselective.restartUi=false`，仍退出并写出重启提示，退出码为 42。

1.1.1 修复默认菜单与存档列表不同导致的来回重启：为存档重启后的首次菜单加载、退出存档回主菜单及后续菜单重载，都保留当前 Java 环境与存档待加载计划，不再因为菜单列表不同强制退出，也不覆盖存档计划。菜单只复用已加载且未改变的共享 Java 模组；菜单新增/更新的 Java 代码暂缓到实际选择游戏时再检查。

再次进入同一套 Java 模组的存档可以继续。实际开始另一套 Java 模组的存档，或者更新、新增、停用该游戏所需 Java 模组，仍可能需要一次完整重启，以免旧 hook 残留。返回菜单不会自动卸载 JVM hook；某些菜单专用 Java 扩展在暂缓阶段可能尚未执行。纯 Lua 模组的差异不触发 Java 重启门槛。

## 源码与许可

原作者为 Andrey “Zed” Zaikin，原 MIT 许可与依赖声明保留。汉化译文沿用老余原 ZombieBuddyCN 项目，字体的 OFL 许可随 JAR 分发。源代码改动见 `source-changes.patch`，完整修改源码另有源码包。`build.ps1` 使用原 Gradle 构建、运行原测试及专项回归，不执行签名任务。需要 JDK 25、Python 与用于编译和验证的游戏 `projectzomboid.jar`。

相关来源：[上游稳定版源码](https://github.com/zed-0xff/ZombieBuddy/tree/v2.3.3)、[HTTP 初始化故障报告 #46](https://github.com/zed-0xff/ZombieBuddy/issues/46)、[官方 2.3.3 发布](https://github.com/zed-0xff/ZombieBuddy/releases/tag/v2.3.3)。

## 1.1.2 审查修正

存档启动计划中的预加载模组在完整重启后执行 PreMain，即使它未在主菜单列表中启用；默认配置启动仍只预加载默认列表中的模组。待加载计划不替代原签名、授权和实际加载检查。暂缓执行的模组显示为待加载，只有本进程已安装的 Java 代码保留活动状态。

从仓库根目录或其他工作目录均可执行 `local-patch/build.ps1 -GameDir "游戏安装目录"`。可用 `-OutputDir` 指定 Gradle 输出目录。脚本生成 JAR 与 SHA-256，运行上游、29 个 JVM 流程、6 组汉化/字体和安装备份检查。`-SkipTests` 仅生成 JAR 与校验文件，不能用于发行打包。构建成功后执行 `python local-patch/tools/package.py`，自动生成源码补丁，并将经过验证的 JAR 和 SHA-256 放入所有发行 ZIP，包括工坊包；不会依赖暂存目录内旧的 JAR。每份回归报告绑定实际验证的 JAR 哈希。发行打包需要 Git checkout 和官方稳定基线提交；建议直接从 fork 克隆构建。源码 ZIP 同时提供已生成的源码补丁。

本轮还通过干净 Git 克隆构建、任意工作目录、带空格的自定义游戏/输出目录，以及 4 项发行流程检查：拒绝未测试构建、拒绝过期回归报告、自动生成缺失补丁、覆盖旧工坊 JAR。可运行 `python local-patch/tools/test-distribution.py` 复查发行流程。
