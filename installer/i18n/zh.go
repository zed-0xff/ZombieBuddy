package i18n

var messagesZH = map[string]string{
	// ── 应用程序 ──
	"app.title":              "ZombieBuddy Windows 安装程序 v%s",
	"app.separator":          "-------------------------------",
	"app.press_enter":        "按回车键退出……",
	"app.install_complete":   "安装完成！你现在可以启动 Steam 并运行 Project Zomboid。",
	"app.uninstall_complete": "卸载完成！你现在可以启动 Steam 并运行 Project Zomboid。",
	"app.nothing_changed":    "未做任何更改。",
	"app.okay_nothing":       "好的，未做任何更改。",
	"app.errors_encountered": "%s 遇到错误，请查看上面的信息。",

	// ── 提示：主操作 ──
	"prompt.action.title":   "我能为你做些什么？",
	"prompt.action.line1":   "  1) 安装 ZombieBuddy",
	"prompt.action.line2":   "  2) 卸载 ZombieBuddy",
	"prompt.action.line3":   "  3) 不用了，谢谢！",
	"prompt.action.hint":    "所有系统更改将在应用前显示以供确认。",
	"prompt.action.prompt":  "请选择 1、2 或 3：",
	"prompt.action.eof_err": "未选择操作",
	"prompt.action.invalid": "请选择 1 安装、2 卸载或 3 不做任何操作。",

	// ── 提示：安装目标 ──
	"prompt.targets.title":   "ZombieBuddy 应修补哪种启动模式？",
	"prompt.targets.line1":   "  1) 两者都修补",
	"prompt.targets.line2":   "  2) 正常启动",
	"prompt.targets.line3":   "  3) 备用启动",
	"prompt.targets.prompt":  "请选择 1、2 或 3：",
	"prompt.targets.eof_err": "未选择启动模式",
	"prompt.targets.invalid": "请选择 1、2 或 3。",

	// ── 提示：正常安装目标 ──
	"prompt.normal.title":   "正常启动应如何修补？",
	"prompt.normal.line1":   "  1) 两者都修补",
	"prompt.normal.line2":   "  2) ProjectZomboid64.json",
	"prompt.normal.line3":   "  3) Steam 启动选项",
	"prompt.normal.prompt":  "请选择 1、2 或 3：",
	"prompt.normal.eof_err": "未选择正常启动修补方式",
	"prompt.normal.invalid": "请选择 1、2 或 3。",

	// ── 确认 ──
	"confirm.no_changes":        "无需进行系统更改。",
	"confirm.will_make_changes": "ZombieBuddy 将进行以下更改：",
	"confirm.prompt":            "是否继续？[y/N]：",

	// ── 操作名称 ──
	"action.install":   "安装",
	"action.uninstall": "卸载",
	"action.nothing":   "无操作",
	"action.operation": "操作",

	// ── 安装消息 ──
	"install.err_select_targets": "选择启动目标时出错：%v",
	"install.err_read_confirm":   "读取确认时出错：%v",
	"install.cancelled":          "安装已取消。",
	"install.err_copy_core":      "复制核心文件时出错：%v",
	"install.success_copy":       "已成功安装 zbNative.dll 和 ZombieBuddy.jar",
	"install.err_json":           "更新 ProjectZomboid64.json 时出错：%v",
	"install.json_updated":       "已为正常启动模式更新 ProjectZomboid64.json",
	"install.json_already":       "ProjectZomboid64.json 已包含 ZombieBuddy agent。",
	"install.err_batch":          "更新 ProjectZomboid64.bat 时出错：%v",
	"install.batch_updated":      "已为备用启动模式更新 ProjectZomboid64.bat",
	"install.batch_already":      "ProjectZomboid64.bat 已包含 ZombieBuddy agent。",
	"install.err_steam":          "更新 Steam 启动选项时出错：%v",

	// ── 卸载消息 ──
	"uninstall.err_plan":      "规划卸载时出错：%v",
	"uninstall.nothing":       "没有可卸载的内容。",
	"uninstall.cancelled":     "卸载已取消。",
	"uninstall.err_apply":     "应用卸载更改时出错：%v",
	"uninstall.err_json":      "更新 ProjectZomboid64.json 时出错：%v",
	"uninstall.json_removed":  `已从 ProjectZomboid64.json 中移除 "%s"`,
	"uninstall.err_batch":     "更新 ProjectZomboid64.bat 时出错：%v",
	"uninstall.batch_removed": `已从 ProjectZomboid64.bat 中移除 "%s"`,
	"uninstall.err_rm_core":   "移除核心文件时出错：%v",
	"uninstall.err_rm_steam":  "移除 Steam 启动选项时出错：%v",

	// ── 路径检测 ──
	"detect.err_steam":   "检测 Steam 时出错：%v",
	"detect.path_format": "[.] %-*s 位于 %s",
	"detect.err_pz":      "检测 Project Zomboid 时出错：%v",
	"detect.err_zb":      "检测 ZombieBuddy 模组时出错：%v",
	"detect.steam_label": "Steam",
	"detect.pz_label":    "PZ",
	"detect.zb_label":    "ZB",

	// ── 预览 / 计划 ──
	"preview.copy":         `复制 "%s\%s"    到 "%s\\"`,
	"preview.add_json":     `将 "%s" 添加到 "%s"`,
	"preview.add_steam":    `将 "%s" 添加到 PZ Steam 启动选项`,
	"preview.remove":       `从 "%[2]s" 中移除 "%[1]s"`,
	"preview.remove_steam": `从 PZ Steam 启动选项中移除 "%s"`,
	"preview.delete":       `删除 "%s"`,

	// ── Steam 关闭提示 ──
	"steam.registry_error":        "找不到 Steam 注册表键",
	"steam.registry_windows_only": "Steam 注册表查找仅支持 Windows",
	"steam.running":               "Steam 正在运行。",
	"steam.close_instructions1":   "    请先完全关闭 Steam 再继续。",
	"steam.close_instructions2":   "    （右键点击系统托盘中的 Steam 图标 -> 退出）",
	"steam.press_enter_after":     "关闭 Steam 后按回车键……",
	"steam.now_closed":            "Steam 已关闭。正在继续%s……",
	"steam.launch_opt_update":     "启动选项更新",

	// ── 路径错误 ──
	"path.pz_not_found": "找不到 Project Zomboid 安装目录",
	"path.zb_not_found": "请确认你已订阅 https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── VDF / 库错误 ──
	"vdf.invalid_library":   "无效的 libraryfolders.vdf",
	"vdf.app_not_found":     "在任何 Steam 库中均未找到该应用",
	"vdf.no_config":         "无法创建 Project Zomboid 启动配置",
	"vdf.already_has_agent": "启动选项已包含 ZombieBuddy agent。",
	"vdf.no_appstate":       "找不到 %s 的 AppState",

	// ── 其他错误 ──
	"err.check_json":            "检查 ProjectZomboid64.json 时出错：%v",
	"err.check_batch":           "检查 ProjectZomboid64.bat 时出错：%v",
	"err.windows_only":          "错误：此安装程序仅适用于 Windows。",
	"err.source_not_found":      "找不到 %s 源文件",
	"err.copy_failed":           "复制 %s 失败：%v",
	"err.remove_failed":         "移除 %s 失败：%v",
	"err.nav_not_map":           "键 %s 不是 map 类型",
	"err.nav_key_not_found":     "未找到键 %s",
	"err.unknown_launch_mode":   "未知的启动模式",
	"err.unknown_normal_target": "未知的正常启动目标",

	// ── 复制 / 移除信息 ──
	"copy.copying":               `正在复制 "%s" 到 "%s"`,
	"remove.removed":             `已移除 %s`,
	"remove.steam_failed_remove": "从 %s 移除启动选项失败：%v",
	"remove.steam_removed":       "已从 %s 移除启动选项",

	// ── Steam 用户消息 ──
	"steam.updated_user":  "已更新用户 %s 的启动选项",
	"steam.failed_user":   "更新用户 %s 的启动选项失败：%v",
	"steam.no_users":      "找不到要更新的用户配置",
	"steam.skipping_user": "跳过用户 %s 的 Steam 启动选项：%v",
}
