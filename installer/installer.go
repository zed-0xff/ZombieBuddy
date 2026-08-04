package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"

	"github.com/andygrunwald/vdf"
	"github.com/zed-0xff/ZombieBuddy/installer/i18n"
)

const (
	PZ_APP_ID         = "108600"
	ZB_MOD_ID         = "3619862853"
	INSTALLER_VERSION = "4.1"
	ZB_LAUNCH_ARG     = "-agentlib:zbNative"
	ZB_LAUNCH_OPTIONS = ZB_LAUNCH_ARG + " --"

	steamLabel = "Steam"
	pzLabel    = "PZ"
	zbLabel    = "ZB"
)

var steamZombieBuddyLaunchOptionPattern = regexp.MustCompile(`(^|\s)-agentlib:zbNative(?:=\S*)?[ ]*[-]*`)

type patchTargets struct {
	normalJSON         bool
	steamLaunchOptions bool
	alternateBatch     bool
}

type promptOption struct {
	value string
	keys  []string
}

type uninstallPlan struct {
	jsonLauncherPath string
	batchFilePath    string
	steamConfigPaths []string
	coreFilePaths    []string
}

type installPaths struct {
	steam string
	pz    string
	zb    string
}

type operationResult int

const (
	resultFailed operationResult = iota
	resultSucceeded
	resultCancelled
)

type launcherConfig struct {
	MainClass string                            `json:"mainClass"`
	Classpath []string                          `json:"classpath"`
	VMArgs    []string                          `json:"vmArgs"`
	Windows   map[string]launcherWindowsVersion `json:"windows,omitempty"`
}

type launcherWindowsVersion struct {
	VMArgs []string `json:"vmArgs"`
}

func main() {
	if runtime.GOOS != "windows" {
		fmt.Println("[!] " + i18n.Translate("err.windows_only"))
		waitForExit()
		return
	}

	i18n.PromptLanguage()

	fmt.Printf(i18n.Translate("app.title")+"\n", INSTALLER_VERSION)
	fmt.Println(i18n.Translate("app.separator"))

	action, err := promptAction()
	if err != nil {
		fmt.Printf("[!] %v\n", err)
		waitForExit()
		return
	}

	result := resultFailed
	switch action {
	case "install":
		result = install()
	case "uninstall":
		result = uninstall()
	case "nothing":
		fmt.Println("\n" + i18n.Translate("app.okay_nothing"))
		waitForExit()
		return
	}

	switch result {
	case resultSucceeded:
		switch action {
		case "install":
			fmt.Println("\n" + i18n.Translate("app.install_complete"))
		case "uninstall":
			fmt.Println("\n" + i18n.Translate("app.uninstall_complete"))
		}
	case resultCancelled:
		fmt.Println("\n" + i18n.Translate("app.nothing_changed"))
	default:
		fmt.Printf("\n"+i18n.Translate("app.errors_encountered")+"\n", displayAction(action))
	}
	waitForExit()
}

func waitForExit() {
	fmt.Println(i18n.Translate("app.press_enter"))
	bufio.NewReader(os.Stdin).ReadBytes('\n')
}

func promptAction() (string, error) {
	reader := bufio.NewReader(os.Stdin)
	return promptChoice(reader, promptChoiceConfig{
		title: i18n.Translate("prompt.action.title"),
		lines: []string{
			i18n.Translate("prompt.action.lines"),
			"",
			i18n.Translate("prompt.action.hint"),
		},
		prompt:  i18n.Translate("prompt.action.prompt"),
		eofErr:  i18n.Translate("prompt.action.eof_err"),
		invalid: i18n.Translate("prompt.action.invalid"),
		options: []promptOption{
			{value: "install", keys: []string{"1", "i", "install"}},
			{value: "uninstall", keys: []string{"2", "u", "uninstall"}},
			{value: "nothing", keys: []string{"3", "n", "nothing", "no", "exit", "quit"}},
		},
	})
}

func promptInstallTargets() (patchTargets, error) {
	reader := bufio.NewReader(os.Stdin)
	value, err := promptChoice(reader, promptChoiceConfig{
		title:   i18n.Translate("prompt.targets.title"),
		lines:   []string{i18n.Translate("prompt.targets.lines")},
		prompt:  i18n.Translate("prompt.targets.prompt"),
		eofErr:  i18n.Translate("prompt.targets.eof_err"),
		invalid: i18n.Translate("prompt.targets.invalid"),
		options: []promptOption{
			{value: "both", keys: []string{"1", "b", "both"}},
			{value: "normal", keys: []string{"2", "n", "normal", "normal launch"}},
			{value: "alternate", keys: []string{"3", "a", "alternate", "alternate launch"}},
		},
	})
	if err != nil {
		return patchTargets{}, err
	}

	switch value {
	case "normal":
		return promptNormalInstallTargets(reader)
	case "alternate":
		return patchTargets{alternateBatch: true}, nil
	case "both":
		return patchTargets{normalJSON: true, steamLaunchOptions: true, alternateBatch: true}, nil
	default:
		return patchTargets{}, fmt.Errorf("%s: %q", i18n.Translate("err.unknown_launch_mode"), value)
	}
}

func promptNormalInstallTargets(reader *bufio.Reader) (patchTargets, error) {
	value, err := promptChoice(reader, promptChoiceConfig{
		title:   i18n.Translate("prompt.normal.title"),
		lines:   []string{i18n.Translate("prompt.normal.lines")},
		prompt:  i18n.Translate("prompt.normal.prompt"),
		eofErr:  i18n.Translate("prompt.normal.eof_err"),
		invalid: i18n.Translate("prompt.normal.invalid"),
		options: []promptOption{
			{value: "both", keys: []string{"1", "b", "both"}},
			{value: "json", keys: []string{"2", "j", "json"}},
			{value: "steam", keys: []string{"3", "s", "steam", "steam launch options"}},
		},
	})
	if err != nil {
		return patchTargets{}, err
	}

	switch value {
	case "json":
		return patchTargets{normalJSON: true}, nil
	case "steam":
		return patchTargets{steamLaunchOptions: true}, nil
	case "both":
		return patchTargets{normalJSON: true, steamLaunchOptions: true}, nil
	default:
		return patchTargets{}, fmt.Errorf("%s: %q", i18n.Translate("err.unknown_normal_target"), value)
	}
}

type promptChoiceConfig struct {
	title   string
	lines   []string
	prompt  string
	eofErr  string
	invalid string
	options []promptOption
}

func promptChoice(reader *bufio.Reader, config promptChoiceConfig) (string, error) {
	for {
		fmt.Println()
		fmt.Println(config.title)
		for _, line := range config.lines {
			fmt.Println(line)
		}
		fmt.Print(config.prompt)

		line, err := reader.ReadString('\n')
		if err != nil && err != io.EOF {
			return "", err
		}
		choice := strings.ToLower(strings.TrimSpace(line))
		for _, option := range config.options {
			for _, key := range option.keys {
				if choice == key {
					return option.value, nil
				}
			}
		}
		if err == io.EOF {
			return "", errors.New(config.eofErr)
		}
		fmt.Println("[!] " + config.invalid)
	}
}

func confirmChanges(lines []string) (bool, error) {
	if len(lines) == 0 {
		fmt.Println()
		fmt.Println(i18n.Translate("confirm.no_changes"))
		return false, nil
	}

	fmt.Println()
	fmt.Println(i18n.Translate("confirm.will_make_changes"))
	for _, line := range lines {
		fmt.Println("  - " + line)
	}
	fmt.Print(i18n.Translate("confirm.prompt"))

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && err != io.EOF {
		return false, err
	}
	switch strings.ToLower(strings.TrimSpace(line)) {
	case "y", "yes":
		return true, nil
	default:
		return false, nil
	}
}

func displayAction(action string) string {
	switch action {
	case "install":
		return i18n.Translate("action.install")
	case "uninstall":
		return i18n.Translate("action.uninstall")
	case "nothing":
		return i18n.Translate("action.nothing")
	default:
		return i18n.Translate("action.operation")
	}
}

func isSteamRunning() bool {
	cmd := exec.Command("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH")
	output, err := cmd.Output()
	if err != nil {
		return false
	}
	return strings.Contains(strings.ToLower(string(output)), "steam.exe")
}

func install() operationResult {
	targets, err := promptInstallTargets()
	if err != nil {
		fmt.Printf("[!] "+i18n.Translate("install.err_select_targets")+"\n", err)
		return resultFailed
	}

	paths, err := detectInstallPaths(true)
	if err != nil {
		fmt.Printf("[!] %v\n", err)
		return resultFailed
	}

	preview := installPreview(paths.pz, paths.steam, paths.zb, targets)
	confirmed, err := confirmChanges(preview)
	if err != nil {
		fmt.Printf("[!] "+i18n.Translate("install.err_read_confirm")+"\n", err)
		return resultFailed
	}
	if !confirmed {
		fmt.Println("[-] " + i18n.Translate("install.cancelled"))
		return resultCancelled
	}

	err = copyCoreFiles(paths.pz, paths.zb)
	if err != nil {
		fmt.Printf("[!] "+i18n.Translate("install.err_copy_core")+"\n", err)
		return resultFailed
	} else {
		fmt.Println("[.] " + i18n.Translate("install.success_copy"))
	}

	if targets.normalJSON {
		updatedJSON, err := patchJSONLauncher(paths.pz)
		if err != nil {
			fmt.Printf("[!] "+i18n.Translate("install.err_json")+"\n", err)
			return resultFailed
		}
		reportChange(updatedJSON, i18n.Translate("install.json_updated"), i18n.Translate("install.json_already"))
	}

	if targets.alternateBatch {
		updatedBatch, err := patchBatchFile(paths.pz)
		if err != nil {
			fmt.Printf("[!] "+i18n.Translate("install.err_batch")+"\n", err)
			return resultFailed
		}
		reportChange(updatedBatch, i18n.Translate("install.batch_updated"), i18n.Translate("install.batch_already"))
	}

	if targets.steamLaunchOptions {
		err = updateLaunchOptions(paths.steam)
		if err != nil {
			fmt.Printf("[!] "+i18n.Translate("install.err_steam")+"\n", err)
			return resultFailed
		}
	}

	return resultSucceeded
}

func uninstall() operationResult {
	paths, err := detectInstallPaths(false)
	if err != nil {
		fmt.Printf("[!] %v\n", err)
		return resultFailed
	}

	plan, err := buildUninstallPlan(paths.pz, paths.steam)
	if err != nil {
		fmt.Printf("[!] "+i18n.Translate("uninstall.err_plan")+"\n", err)
		return resultFailed
	}
	previewLines := uninstallPreview(plan)
	if len(previewLines) == 0 {
		fmt.Println("[-] " + i18n.Translate("uninstall.nothing"))
		return resultCancelled
	}

	confirmed, err := confirmChanges(previewLines)
	if err != nil {
		fmt.Printf("[!] "+i18n.Translate("install.err_read_confirm")+"\n", err)
		return resultFailed
	}
	if !confirmed {
		fmt.Println("[-] " + i18n.Translate("uninstall.cancelled"))
		return resultCancelled
	}

	if err := applyUninstallPlan(plan); err != nil {
		fmt.Printf("[!] "+i18n.Translate("uninstall.err_apply")+"\n", err)
		return resultFailed
	}

	return resultSucceeded
}

func detectInstallPaths(includeZB bool) (installPaths, error) {
	maxLen := len(steamLabel)

	steamPath, err := detectSteamPath()
	if err != nil {
		return installPaths{}, fmt.Errorf("Error detecting Steam: %v", err)
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, steamLabel, steamPath)

	pzPath, err := detectPZPath(steamPath)
	if err != nil {
		return installPaths{}, fmt.Errorf("Error detecting Project Zomboid: %v", err)
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, pzLabel, pzPath)

	paths := installPaths{steam: steamPath, pz: pzPath}
	if includeZB {
		zbPath, err := detectZBPath(steamPath)
		if err != nil {
			return installPaths{}, fmt.Errorf("Error detecting ZombieBuddy mod: %v", err)
		}
		fmt.Printf("[.] %-*s is at %s\n", maxLen, zbLabel, zbPath)
		paths.zb = zbPath
	}
	return paths, nil
}

func buildUninstallPlan(pzPath string, steamPath string) (uninstallPlan, error) {
	plan := uninstallPlan{
		coreFilePaths: coreFileDeletionPlan(pzPath),
	}

	jsonPath := filepath.Join(pzPath, "ProjectZomboid64.json")
	hasJSON, err := jsonLauncherHasZombieBuddy(jsonPath)
	if err != nil {
		return uninstallPlan{}, errors.New(fmt.Sprintf(i18n.Translate("err.check_json"), err))
	}
	if hasJSON {
		plan.jsonLauncherPath = jsonPath
	}

	batchPath := filepath.Join(pzPath, "ProjectZomboid64.bat")
	hasBatch, err := batchFileHasZombieBuddy(batchPath)
	if err != nil {
		return uninstallPlan{}, errors.New(fmt.Sprintf(i18n.Translate("err.check_batch"), err))
	}
	if hasBatch {
		plan.batchFilePath = batchPath
	}

	steamConfigPaths, err := steamLaunchOptionRemovalPlan(steamPath)
	if err != nil {
		return uninstallPlan{}, err
	}
	plan.steamConfigPaths = steamConfigPaths

	return plan, nil
}

func applyUninstallPlan(plan uninstallPlan) error {
	if plan.jsonLauncherPath != "" {
		updated, err := updateJSONLauncherVMArgs(plan.jsonLauncherPath, false)
		if err != nil {
			return errors.New(fmt.Sprintf(i18n.Translate("uninstall.err_json"), err))
		}
		reportChange(updated, fmt.Sprintf(i18n.Translate("uninstall.json_removed"), ZB_LAUNCH_ARG), "")
	}

	if plan.batchFilePath != "" {
		updated, err := updateBatchJavaOptions(plan.batchFilePath, false)
		if err != nil {
			return errors.New(fmt.Sprintf(i18n.Translate("uninstall.err_batch"), err))
		}
		reportChange(updated, fmt.Sprintf(i18n.Translate("uninstall.batch_removed"), ZB_LAUNCH_ARG), "")
	}

	if err := removeCoreFiles(plan.coreFilePaths); err != nil {
		return errors.New(fmt.Sprintf(i18n.Translate("uninstall.err_rm_core"), err))
	}

	if err := removeLaunchOptions(plan.steamConfigPaths); err != nil {
		return errors.New(fmt.Sprintf(i18n.Translate("uninstall.err_rm_steam"), err))
	}

	return nil
}

func reportChange(changed bool, changedMessage string, unchangedMessage string) {
	if changed {
		fmt.Println("[.] " + changedMessage)
	} else if unchangedMessage != "" {
		fmt.Println("[-] " + unchangedMessage)
	}
}

func installPreview(pzPath string, steamPath string, zbPath string, targets patchTargets) []string {
	lines := []string{
		fmt.Sprintf(i18n.Translate("preview.copy"), zbLabel, "zbNative.dll", pzLabel),
		fmt.Sprintf(i18n.Translate("preview.copy"), zbLabel, "ZombieBuddy.jar", pzLabel),
	}
	if targets.normalJSON {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.add_json"), ZB_LAUNCH_ARG, "ProjectZomboid64.json"))
	}
	if targets.alternateBatch {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.add_json"), ZB_LAUNCH_ARG, "ProjectZomboid64.bat"))
	}
	if targets.steamLaunchOptions {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.add_steam"), ZB_LAUNCH_OPTIONS))
	}
	return lines
}

func uninstallPreview(plan uninstallPlan) []string {
	var lines []string
	if plan.jsonLauncherPath != "" {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.remove"), ZB_LAUNCH_ARG, plan.jsonLauncherPath))
	}
	if plan.batchFilePath != "" {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.remove"), ZB_LAUNCH_ARG, plan.batchFilePath))
	}
	if plan.steamConfigPaths != nil {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.remove_steam"), ZB_LAUNCH_ARG))
	}
	for _, path := range plan.coreFilePaths {
		lines = append(lines, fmt.Sprintf(i18n.Translate("preview.delete"), path))
	}
	return lines
}

func waitForSteamToClose(operation string) {
	steamWasRunning := false
	for isSteamRunning() {
		steamWasRunning = true
		fmt.Println("\n[!] " + i18n.Translate("steam.running"))
		fmt.Println(i18n.Translate("steam.close_instructions1"))
		fmt.Println(i18n.Translate("steam.close_instructions2"))
		fmt.Println("\n" + i18n.Translate("steam.press_enter_after"))
		bufio.NewReader(os.Stdin).ReadBytes('\n')
	}
	if steamWasRunning {
		fmt.Printf("[.] "+i18n.Translate("steam.now_closed")+"\n\n", operation)
	}
}

func detectPZPath(steamPath string) (string, error) {
	// First check the default path
	defaultPath := filepath.Join(steamPath, "steamapps", "common", "ProjectZomboid")
	if _, err := os.Stat(defaultPath); err == nil {
		return defaultPath, nil
	}

	// Then check libraryfolders.vdf
	libraryVDF := filepath.Join(steamPath, "steamapps", "libraryfolders.vdf")
	if _, err := os.Stat(libraryVDF); err == nil {
		return findAppInLibraries(libraryVDF, "common", "ProjectZomboid")
	}

	return "", errors.New(i18n.Translate("path.pz_not_found"))
}

func detectZBPath(steamPath string) (string, error) {
	// First check the default path
	defaultPath := filepath.Join(steamPath, "steamapps", "workshop", "content", PZ_APP_ID, ZB_MOD_ID)
	if _, err := os.Stat(defaultPath); err == nil {
		return defaultPath, nil
	}

	// Then check libraryfolders.vdf
	libraryVDF := filepath.Join(steamPath, "steamapps", "libraryfolders.vdf")
	if _, err := os.Stat(libraryVDF); err == nil {
		return findAppInLibraries(libraryVDF, "workshop", "content", PZ_APP_ID, ZB_MOD_ID)
	}

	return "", errors.New(i18n.Translate("path.zb_not_found"))
}

func findAppInLibraries(libraryVDFPath string, subPath ...string) (string, error) {
	m, err := parseVDFMap(libraryVDFPath)
	if err != nil {
		return "", err
	}

	libraryfolders, ok := m["libraryfolders"].(map[string]interface{})
	if !ok {
		return "", errors.New(i18n.Translate("vdf.invalid_library"))
	}

	for _, folder := range libraryfolders {
		folderMap, ok := folder.(map[string]interface{})
		if !ok {
			continue
		}

		path, ok := folderMap["path"].(string)
		if !ok {
			continue
		}

		apps, ok := folderMap["apps"].(map[string]interface{})
		if !ok {
			continue
		}

		if _, found := apps[PZ_APP_ID]; found {
			fullSubPath := append([]string{"steamapps"}, subPath...)
			fullPath := filepath.Join(path, filepath.Join(fullSubPath...))
			if _, err := os.Stat(fullPath); err == nil {
				return fullPath, nil
			}
		}
	}

	return "", errors.New(i18n.Translate("vdf.app_not_found"))
}

func copyCoreFiles(pzPath string, zbPath string) error {
	files := []string{"zbNative.dll", "ZombieBuddy.jar"}

	for _, filename := range files {
		var sources []string
		if zbPath != "" {
			sources = append(sources, filepath.Join(zbPath, "mods", "ZombieBuddy", "libs", filename))
		}

		var srcPath string
		for _, s := range sources {
			if _, err := os.Stat(s); err == nil {
				srcPath = s
				break
			}
		}

		if srcPath == "" {
			return errors.New(fmt.Sprintf(i18n.Translate("err.source_not_found"), filename))
		}

		dstPath := filepath.Join(pzPath, filename)
		fmt.Printf("[.] "+i18n.Translate("copy.copying")+"\n", srcPath, dstPath)
		err := copyFile(srcPath, dstPath)
		if err != nil {
			return errors.New(fmt.Sprintf(i18n.Translate("err.copy_failed"), filename, err))
		}
	}

	return nil
}

func removeCoreFiles(paths []string) error {
	for _, path := range paths {
		err := os.Remove(path)
		if err == nil {
			fmt.Printf("[.] "+i18n.Translate("remove.removed")+"\n", path)
			continue
		}
		if os.IsNotExist(err) {
			continue
		}
		return errors.New(fmt.Sprintf(i18n.Translate("err.remove_failed"), path, err))
	}
	return nil
}

func coreFileDeletionPlan(pzPath string) []string {
	var paths []string
	for _, filename := range coreFilesForRemoval() {
		path := filepath.Join(pzPath, filename)
		if _, err := os.Stat(path); err == nil {
			paths = append(paths, path)
		}
	}
	return paths
}

func coreFilesForRemoval() []string {
	return []string{"zbNative.dll", "ZombieBuddy.jar", "ZombieBuddy.jar.new"}
}

func patchJSONLauncher(pzPath string) (bool, error) {
	return updateJSONLauncherVMArgs(filepath.Join(pzPath, "ProjectZomboid64.json"), true)
}

func unpatchJSONLauncher(pzPath string) (bool, error) {
	return updateJSONLauncherVMArgs(filepath.Join(pzPath, "ProjectZomboid64.json"), false)
}

func updateJSONLauncherVMArgs(path string, install bool) (bool, error) {
	var changed bool
	return updateJSONLauncher(path, func(cfg *launcherConfig) bool {
		if install {
			cfg.VMArgs, changed = addZombieBuddyVMArg(cfg.VMArgs)
		} else {
			cfg.VMArgs, changed = removeZombieBuddyVMArg(cfg.VMArgs)
		}
		return changed
	})
}

func updateJSONLauncher(path string, mutate func(*launcherConfig) bool) (bool, error) {
	cfg, found, err := readJSONLauncher(path)
	if err != nil || !found {
		return false, err
	}
	if !mutate(&cfg) {
		return false, nil
	}
	output, err := json.MarshalIndent(cfg, "", "\t")
	if err != nil {
		return false, err
	}
	output = append(output, '\n')
	return true, os.WriteFile(path, output, 0644)
}

func jsonLauncherHasZombieBuddy(path string) (bool, error) {
	cfg, found, err := readJSONLauncher(path)
	if err != nil || !found {
		return false, err
	}
	return hasZombieBuddyVMArg(cfg.VMArgs), nil
}

func readJSONLauncher(path string) (launcherConfig, bool, error) {
	input, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return launcherConfig{}, false, nil
		}
		return launcherConfig{}, false, err
	}

	var cfg launcherConfig
	if err := json.Unmarshal(input, &cfg); err != nil {
		return launcherConfig{}, false, err
	}
	return cfg, true, nil
}

func addZombieBuddyVMArg(args []string) ([]string, bool) {
	if hasZombieBuddyVMArg(args) {
		return args, false
	}
	return append([]string{ZB_LAUNCH_ARG}, args...), true
}

func hasZombieBuddyVMArg(args []string) bool {
	for _, arg := range args {
		if isZombieBuddyJavaOption(arg) {
			return true
		}
	}
	return false
}

func removeZombieBuddyVMArg(args []string) ([]string, bool) {
	filtered := args[:0]
	changed := false
	for _, arg := range args {
		if isZombieBuddyJavaOption(arg) {
			changed = true
			continue
		}
		filtered = append(filtered, arg)
	}
	if !changed {
		return args, false
	}
	return filtered, true
}

func patchBatchFile(pzPath string) (bool, error) {
	return updateBatchJavaOptions(filepath.Join(pzPath, "ProjectZomboid64.bat"), true)
}

func unpatchBatchFile(pzPath string) (bool, error) {
	return updateBatchJavaOptions(filepath.Join(pzPath, "ProjectZomboid64.bat"), false)
}

func updateBatchJavaOptions(path string, install bool) (bool, error) {
	return updateBatchJavaOptionsValue(path, func(currentValue string) (string, bool) {
		if install {
			return addZombieBuddyJavaOption(currentValue)
		}
		return removeZombieBuddyJavaOption(currentValue)
	}, install)
}

func updateBatchJavaOptionsValue(path string, mutate func(string) (string, bool), addIfMissing bool) (bool, error) {
	input, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}

	content := string(input)
	newline := "\n"
	if strings.Contains(content, "\r\n") {
		newline = "\r\n"
	} else if strings.Contains(content, "\r") {
		newline = "\r"
	}

	normalized := strings.ReplaceAll(strings.ReplaceAll(content, "\r\n", "\n"), "\r", "\n")
	lines := strings.Split(normalized, "\n")
	updated := false
	foundJavaOptions := false

	for i, line := range lines {
		keyStart, valueStart, ok := javaOptionsAssignment(line)
		if !ok {
			continue
		}

		foundJavaOptions = true
		currentValue := strings.TrimSpace(line[valueStart:])
		newValue, changed := mutate(currentValue)
		if changed {
			lines[i] = line[:keyStart] + "SET _JAVA_OPTIONS=" + newValue
			updated = true
		}
		break
	}

	if addIfMissing && !foundJavaOptions {
		lines = append([]string{"SET _JAVA_OPTIONS=" + ZB_LAUNCH_ARG}, lines...)
		updated = true
	}

	if !updated {
		return false, nil
	}
	return true, os.WriteFile(path, []byte(strings.Join(lines, newline)), 0644)
}

func batchFileHasZombieBuddy(path string) (bool, error) {
	value, found, err := readBatchJavaOptions(path)
	if err != nil || !found {
		return false, err
	}
	return containsZombieBuddyJavaOption(value), nil
}

func readBatchJavaOptions(path string) (string, bool, error) {
	input, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", false, nil
		}
		return "", false, err
	}

	normalized := strings.ReplaceAll(strings.ReplaceAll(string(input), "\r\n", "\n"), "\r", "\n")
	for _, line := range strings.Split(normalized, "\n") {
		_, valueStart, ok := javaOptionsAssignment(line)
		if !ok {
			continue
		}
		return strings.TrimSpace(line[valueStart:]), true, nil
	}
	return "", false, nil
}

func javaOptionsAssignment(line string) (int, int, bool) {
	trimmedLeft := strings.TrimLeft(line, " \t")
	keyStart := len(line) - len(trimmedLeft)
	upper := strings.ToUpper(trimmedLeft)
	const prefix = "SET _JAVA_OPTIONS="
	if !strings.HasPrefix(upper, prefix) {
		return 0, 0, false
	}
	return keyStart, keyStart + len(prefix), true
}

func addZombieBuddyJavaOption(options string) (string, bool) {
	if containsZombieBuddyJavaOption(options) {
		return options, false
	}
	if strings.TrimSpace(options) == "" {
		return ZB_LAUNCH_ARG, true
	}
	return ZB_LAUNCH_ARG + " " + options, true
}

func removeZombieBuddyJavaOption(options string) (string, bool) {
	fields := strings.Fields(options)
	filtered := fields[:0]
	changed := false
	for _, field := range fields {
		if isZombieBuddyJavaOption(field) {
			changed = true
			continue
		}
		filtered = append(filtered, field)
	}
	if !changed {
		return options, false
	}
	return strings.Join(filtered, " "), true
}

func containsZombieBuddyJavaOption(options string) bool {
	for _, field := range strings.Fields(options) {
		if isZombieBuddyJavaOption(field) {
			return true
		}
	}
	return false
}

func isZombieBuddyJavaOption(value string) bool {
	return value == ZB_LAUNCH_ARG || strings.HasPrefix(value, ZB_LAUNCH_ARG+"=")
}

func copyFile(src, dst string) error {
	sourceFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer sourceFile.Close()

	destFile, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer destFile.Close()

	_, err = io.Copy(destFile, sourceFile)
	return err
}

func updateLaunchOptions(steamPath string) error {
	userdataPath := filepath.Join(steamPath, "userdata")
	entries, err := os.ReadDir(userdataPath)
	if err != nil {
		return err
	}

	updatedCount := 0
	for _, entry := range entries {
		if entry.IsDir() {
			localConfigPath := filepath.Join(userdataPath, entry.Name(), "config", "localconfig.vdf")
			if _, err := os.Stat(localConfigPath); err == nil {
				updated, err := patchVDF(localConfigPath)
				if err == nil {
					if updated {
						fmt.Printf("[.] Updated launch options for user %s\n", entry.Name())
					}
					updatedCount++
				} else {
					fmt.Printf("[!] Failed to update launch options for user %s: %v\n", entry.Name(), err)
				}
			}
		}
	}

	if updatedCount == 0 {
		return errors.New(i18n.Translate("steam.no_users"))
	}
	return nil
}

func removeLaunchOptions(localConfigPaths []string) error {
	if len(localConfigPaths) == 0 {
		return nil
	}

	waitForSteamToClose(i18n.Translate("steam.launch_opt_update"))
	for _, localConfigPath := range localConfigPaths {
		updated, err := unpatchVDF(localConfigPath, false)
		if err != nil {
			return errors.New(fmt.Sprintf(i18n.Translate("remove.steam_failed_remove"), localConfigPath, err))
		}
		if updated {
			fmt.Printf("[.] "+i18n.Translate("remove.steam_removed")+"\n", localConfigPath)
		}
	}
	return nil
}

func steamLaunchOptionRemovalPlan(steamPath string) ([]string, error) {
	userdataPath := filepath.Join(steamPath, "userdata")
	entries, err := os.ReadDir(userdataPath)
	if err != nil {
		return nil, err
	}

	var localConfigPaths []string
	for _, entry := range entries {
		if entry.IsDir() {
			localConfigPath := filepath.Join(userdataPath, entry.Name(), "config", "localconfig.vdf")
			if _, err := os.Stat(localConfigPath); err == nil {
				has, err := vdfHasZombieBuddyLaunchOptions(localConfigPath)
				if err != nil {
					fmt.Printf("[?] "+i18n.Translate("steam.skipping_user")+"\n", entry.Name(), err)
					continue
				}
				if has {
					localConfigPaths = append(localConfigPaths, localConfigPath)
				}
			}
		}
	}
	return localConfigPaths, nil
}

func patchVDF(path string) (bool, error) {
	pz, err := findPZLaunchConfig(path, true)
	if err != nil {
		return false, err
	}

	if pz == nil {
		return false, errors.New(i18n.Translate("vdf.no_config"))
	}

	currentOptions := launchOptionsFromConfig(pz)

	newOptions := ZB_LAUNCH_OPTIONS
	if hasZombieBuddyLaunchOptions(currentOptions) {
		fmt.Println("[-] " + i18n.Translate("vdf.already_has_agent"))
		return false, nil
	}

	waitForSteamToClose(i18n.Translate("steam.launch_opt_update"))
	return true, manualPatchVDF(path, currentOptions, newOptions)
}

func unpatchVDF(path string, waitForSteam bool) (bool, error) {
	currentOptions, err := readPZLaunchOptions(path)
	if err != nil {
		return false, err
	}

	newOptions, changed := stripZombieBuddyLaunchOptions(currentOptions)
	if !changed {
		return false, nil
	}

	if waitForSteam {
		waitForSteamToClose(i18n.Translate("steam.launch_opt_update"))
	}
	return true, manualPatchVDF(path, currentOptions, newOptions)
}

func vdfHasZombieBuddyLaunchOptions(path string) (bool, error) {
	currentOptions, err := readPZLaunchOptions(path)
	if err != nil {
		return false, err
	}
	return hasZombieBuddyLaunchOptions(currentOptions), nil
}

func readPZLaunchOptions(path string) (string, error) {
	pz, err := findPZLaunchConfig(path, false)
	if err != nil || pz == nil {
		return "", err
	}
	return launchOptionsFromConfig(pz), nil
}

func findPZLaunchConfig(path string, create bool) (map[string]interface{}, error) {
	m, err := parseVDFMap(path)
	if err != nil {
		return nil, err
	}

	apps, err := navigateMap(m, "UserLocalConfigStore", "Software", "Valve", "Steam", "Apps")
	if err != nil {
		return nil, fmt.Errorf("%v (root keys: %s)", err, formatMapKeys(m))
	}

	var pz map[string]interface{}
	for k, v := range apps {
		if strings.EqualFold(k, PZ_APP_ID) {
			pz, _ = v.(map[string]interface{})
			break
		}
	}
	if pz == nil {
		if !create {
			return nil, nil
		}
		pz = make(map[string]interface{})
		apps[PZ_APP_ID] = pz
	}
	return pz, nil
}

func launchOptionsFromConfig(pz map[string]interface{}) string {
	for k, v := range pz {
		if strings.EqualFold(k, "LaunchOptions") {
			currentOptions, _ := v.(string)
			return currentOptions
		}
	}
	return ""
}

func formatMapKeys(m map[string]interface{}) string {
	if len(m) == 0 {
		return "none"
	}

	keys := make([]string, 0, len(m))
	for key := range m {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return strings.Join(keys, ", ")
}

func hasZombieBuddyLaunchOptions(options string) bool {
	_, changed := stripZombieBuddyLaunchOptions(options)
	return changed
}

func stripZombieBuddyLaunchOptions(options string) (string, bool) {
	newOptions := strings.Join(strings.Fields(steamZombieBuddyLaunchOptionPattern.ReplaceAllString(options, " ")), " ")
	if newOptions == strings.TrimSpace(options) {
		return options, false
	}
	return newOptions, true
}

func parseVDFMap(path string) (map[string]interface{}, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	p := vdf.NewParser(f)
	return p.Parse()
}

func navigateMap(m map[string]interface{}, path ...string) (map[string]interface{}, error) {
	current := m
	for _, key := range path {
		found := false
		for k, v := range current {
			if strings.EqualFold(k, key) {
				next, ok := v.(map[string]interface{})
				if !ok {
					return nil, errors.New(fmt.Sprintf(i18n.Translate("err.nav_not_map"), k))
				}
				current = next
				found = true
				break
			}
		}
		if !found {
			return nil, errors.New(fmt.Sprintf(i18n.Translate("err.nav_key_not_found"), key))
		}
	}
	return current, nil
}

func manualPatchVDF(path, oldOpts, newOpts string) error {
	input, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	lines := strings.Split(string(input), "\n")
	foundApp := false
	inApp := 0

	var output []string
	launchOptionsFound := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)

		if !foundApp && (strings.EqualFold(trimmed, "\""+PZ_APP_ID+"\"") || strings.EqualFold(trimmed, PZ_APP_ID)) {
			foundApp = true
		}

		if foundApp {
			if strings.Contains(line, "{") {
				inApp++
			}
			if strings.Contains(line, "}") {
				inApp--
				if inApp == 0 {
					if !launchOptionsFound {
						// Add launch options before closing the app block
						output = append(output, fmt.Sprintf("\t\t\t\t\t\"LaunchOptions\"\t\t\"%s\"", newOpts))
					}
					foundApp = false
				}
			}

			if inApp > 0 && strings.Contains(strings.ToLower(trimmed), "\"launchoptions\"") {
				// Replace existing launch options
				parts := strings.SplitN(line, "\"", 4)
				if len(parts) >= 4 {
					line = parts[0] + "\"" + parts[1] + "\"" + parts[2] + "\"" + newOpts + "\""
					launchOptionsFound = true
				}
			}
		}
		output = append(output, line)
	}

	return os.WriteFile(path, []byte(strings.Join(output, "\n")), 0644)
}
