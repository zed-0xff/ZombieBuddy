# ZombieBuddy 2.3.3 — LY Optimized 1.0.0

基于官方稳定版 **v2.3.3 / commit `0ddf161c27848f12d09e74de7fadbea9d50e621d`** 的源码修复与优化版本。已使用本机 **Project Zomboid 42.21 / 游戏自带 Java 25** 验证。GitHub master 当前为 3.0.0 alpha，本包不引入该分支的大规模重构。

**这是原作者尚未发布新版修复期间制作的临时修复与性能优化包。原作者发布更新后，本创意工坊 Mod 将删除；届时请回到原作者的官方版本。**

Fork 仓库：[yuruichang/ZombieBuddy](https://github.com/yuruichang/ZombieBuddy)；本次源码分支：[codex/optimized-b42](https://github.com/yuruichang/ZombieBuddy/tree/codex/optimized-b42)。

## 新版故障的原因

42.21 的 `ZomboidFileSystem.loadMods` 参数是 `List<String>`。原框架的加载 Advice 声明为 `ArrayList<String>`，无法匹配目标方法，所以能看到框架启动，却没有 Java 模组加载。本包将 Advice 和内部加载逻辑改为 `List`，同时保留 `Loader.loadMods(ArrayList)` 的旧入口，兼容已编译的旧调用者。

依据：[上游 Issue #53](https://github.com/zed-0xff/ZombieBuddy/issues/53)、[未合并 PR #56](https://github.com/zed-0xff/ZombieBuddy/pull/56)。另对比了 [工坊临时修复 3807686870](https://steamcommunity.com/sharedfiles/filedetails/?id=3807686870) 的实际 JAR，确认其加载器修复方向相同；本包由官方稳定版源码构建，没有直接改用他人的二进制。

## 性能与兼容性改动

- 扫描每个 Java 模组时，只扫描当前 JAR，减少重复扫描全部已加载模组的工作；保留 ClassGraph、LuaClass 和全局 LuaMethod 处理。
- 每个补丁转换器先按目标类快速过滤，再进入 ByteBuddy。精确名称、前后缀、通配符和正则目标保留原含义。回调中使用普通循环，避免惰性 Stream 类加载触发循环加载错误。
- ByteBuddy 安装时已重新转换已加载的目标类，移除之后的第二次重复转换；保留必要的首次转换和 warmUp 类初始化。
- 合并之前独立优化补丁的重启约束。新获准、更新或本次运行重新启用的 Java 模组先记录、不执行；审批批次结束后提示重启并退出。已加载模组被停用或拒绝，也要求重启以清除旧 hook。
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

另有 **13 个专项 JVM 场景**通过，包括：新模组延迟执行、重启后 Advice/MethodDelegation、已加载类只转换一次、正则目标、Lua API 暴露、更新与重新启用、停用退出、存档列表衔接、42.21 真实加载入口、惰性 HTTP、初始化失败回退以及原 `zbNative.dll` 启动。JVM 验证启用 `-Xverify:all`。

没有进入完整游戏世界或进行全部模组组合和多人实机验收；未测量完整启动耗时，不承诺提速百分比。本包修复框架入口，不能自动修复其他 Java 模组对新版游戏 API 的不兼容。

缓存位于 ZombieBuddy 配置目录的 `selective-hooks.properties`；不改原审批文件。自定义游戏 `-cachedir` 时，可增加 `-Dzbselective.defaultMods=实际缓存目录/mods/default.txt`，保证启动前的预加载启用判断读取正确清单。可用 `-Dzbselective.state=完整路径` 独立指定待加载文件。无界面服务器用 `-Dzbselective.restartUi=false`，仍退出并写出重启提示，退出码为 42。

为存档列表重启后，第一次默认菜单加载暂缓 Java Main，保留存档的待加载计划；选相同存档后正常加载。这个等待阶段的 Java 菜单扩展可能尚未执行，Lua 菜单模组照常加载。

## 源码与许可

原作者为 Andrey “Zed” Zaikin，原 MIT 许可与依赖声明保留。源代码改动见 `source-changes.patch`，完整修改源码另有源码包。`build.ps1` 使用原 Gradle 构建、运行原测试及专项回归，不执行签名任务。需要 JDK 25、Python 与用于编译和验证的游戏 `projectzomboid.jar`。

相关来源：[上游稳定版源码](https://github.com/zed-0xff/ZombieBuddy/tree/v2.3.3)、[HTTP 初始化故障报告 #46](https://github.com/zed-0xff/ZombieBuddy/issues/46)、[官方 2.3.3 发布](https://github.com/zed-0xff/ZombieBuddy/releases/tag/v2.3.3)。
