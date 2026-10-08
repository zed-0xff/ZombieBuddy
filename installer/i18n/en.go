package i18n

var messagesEN = map[string]string{
	// ── Application ──
	"app.title":              "ZombieBuddy Windows Installer v%s",
	"app.separator":          "-------------------------------",
	"app.press_enter":        "Press Enter to exit...",
	"app.install_complete":   "Installation complete! You can now start Steam and launch Project Zomboid.",
	"app.uninstall_complete": "Uninstall complete! You can now start Steam and launch Project Zomboid.",
	"app.nothing_changed":    "Nothing changed.",
	"app.okay_nothing":       "Okay, nothing changed.",
	"app.errors_encountered": "%s encountered errors. Please review the messages above.",

	// ── Prompt: main action ──
	"prompt.action.title":   "What can I do you for?",
	"prompt.action.lines":   "  1) Install ZombieBuddy\n  2) Uninstall ZombieBuddy\n  3) Nothing, thanks!",
	"prompt.action.hint":    "Any system changes will be shown for confirmation before they are applied.",
	"prompt.action.prompt":  "Choose 1, 2, or 3: ",
	"prompt.action.eof_err": "no action selected",
	"prompt.action.invalid": "Please choose 1 for install, 2 for uninstall, or 3 to do nothing.",

	// ── Prompt: install targets ──
	"prompt.targets.title":   "What launch mode should ZombieBuddy patch?",
	"prompt.targets.lines":   "  1) Both\n  2) Normal Launch\n  3) Alternate Launch",
	"prompt.targets.prompt":  "Choose 1, 2, or 3: ",
	"prompt.targets.eof_err": "no launch mode selected",
	"prompt.targets.invalid": "Please choose 1, 2, or 3.",

	// ── Prompt: normal install targets ──
	"prompt.normal.title":   "How should Normal Launch be patched?",
	"prompt.normal.lines":   "  1) Both\n  2) ProjectZomboid64.json\n  3) Steam launch options",
	"prompt.normal.prompt":  "Choose 1, 2, or 3: ",
	"prompt.normal.eof_err": "no normal launch patch selected",
	"prompt.normal.invalid": "Please choose 1, 2, or 3.",

	// ── Confirmation ──
	"confirm.no_changes":        "No system changes are needed.",
	"confirm.will_make_changes": "ZombieBuddy will make these changes:",
	"confirm.prompt":            "Continue? [y/N]: ",

	// ── Action names ──
	"action.install":   "Installation",
	"action.uninstall": "Uninstall",
	"action.nothing":   "Nothing",
	"action.operation": "Operation",

	// ── Install messages ──
	"install.err_select_targets": "Error selecting launch targets: %v",
	"install.err_read_confirm":   "Error reading confirmation: %v",
	"install.cancelled":          "Installation cancelled.",
	"install.err_copy_core":      "Error copying core files: %v",
	"install.success_copy":       "Successfully installed zbNative.dll and ZombieBuddy.jar",
	"install.err_json":           "Error updating ProjectZomboid64.json: %v",
	"install.json_updated":       "Updated ProjectZomboid64.json for normal launcher mode",
	"install.json_already":       "ProjectZomboid64.json already contains ZombieBuddy agent.",
	"install.err_batch":          "Error updating ProjectZomboid64.bat: %v",
	"install.batch_updated":      "Updated ProjectZomboid64.bat for alternative launcher mode",
	"install.batch_already":      "ProjectZomboid64.bat already contains ZombieBuddy agent.",
	"install.err_steam":          "Error updating Steam launch options: %v",

	// ── Uninstall messages ──
	"uninstall.err_plan":      "Error planning uninstall: %v",
	"uninstall.nothing":       "Nothing to uninstall.",
	"uninstall.cancelled":     "Uninstall cancelled.",
	"uninstall.err_apply":     "Error applying uninstall changes: %v",
	"uninstall.err_json":      "updating ProjectZomboid64.json: %v",
	"uninstall.json_removed":  `Removed "%s" from ProjectZomboid64.json`,
	"uninstall.err_batch":     "updating ProjectZomboid64.bat: %v",
	"uninstall.batch_removed": `Removed "%s" from ProjectZomboid64.bat`,
	"uninstall.err_rm_core":   "removing core files: %v",
	"uninstall.err_rm_steam":  "removing Steam launch options: %v",

	// ── Preview / plan ──
	"preview.copy":         `copy "%s\%s"    to "%s\\"`,
	"preview.add_json":     `add "%s" to "%s"`,
	"preview.add_steam":    `add "%s" to PZ Steam launch options`,
	"preview.remove":       `remove "%s" from "%s"`,
	"preview.remove_steam": `remove "%s" from PZ Steam launch options`,
	"preview.delete":       `delete "%s"`,

	// ── Steam close prompt ──
	"steam.registry_error":        "could not find Steam registry key",
	"steam.registry_windows_only": "Steam registry lookup is only supported on Windows",
	"steam.running":               "Steam is currently running.",
	"steam.close_instructions1":   "    Please close Steam completely before continuing.",
	"steam.close_instructions2":   "    (Right-click Steam in system tray -> Exit)",
	"steam.press_enter_after":     "Press Enter after closing Steam...",
	"steam.now_closed":            "Steam is now closed. Continuing %s...",
	"steam.launch_opt_update":     "launch option update",

	// ── Path errors ──
	"path.pz_not_found": "could not find Project Zomboid installation",
	"path.zb_not_found": "Make sure you've subscribed to https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853",

	// ── VDF / library errors ──
	"vdf.invalid_library":   "invalid libraryfolders.vdf",
	"vdf.app_not_found":     "app not found in any Steam library",
	"vdf.no_config":         "could not create Project Zomboid launch config",
	"vdf.already_has_agent": "Launch options already contain ZombieBuddy agent.",
	"vdf.no_appstate":       "could not find AppState for %s",

	// ── Other errors ──
	"err.check_json":            "checking ProjectZomboid64.json: %v",
	"err.check_batch":           "checking ProjectZomboid64.bat: %v",
	"err.windows_only":          "Error: This installer is only designed for Windows.",
	"err.source_not_found":      "could not find %s source",
	"err.copy_failed":           "failed to copy %s: %v",
	"err.remove_failed":         "failed to remove %s: %v",
	"err.nav_not_map":           "key %s is not a map",
	"err.nav_key_not_found":     "key %s not found",
	"err.unknown_launch_mode":   "unknown launch mode",
	"err.unknown_normal_target": "unknown normal launch target",

	// ── Copy / remove info ──
	"copy.copying":               `copying "%s" to "%s"`,
	"remove.removed":             `Removed %s`,
	"remove.steam_failed_remove": "failed to remove launch options from %s: %v",
	"remove.steam_removed":       "Removed launch options from %s",

	// ── Steam user messages ──
	"steam.updated_user":  "Updated launch options for user %s",
	"steam.failed_user":   "Failed to update launch options for user %s: %v",
	"steam.no_users":      "no user configurations found to update",
	"steam.skipping_user": "skipping Steam launch options for user %s: %v",
}
