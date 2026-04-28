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

	"github.com/jslay88/vdf"
)

const (
	PZ_APP_ID         = "108600"
	ZB_MOD_ID         = "3619862853"
	INSTALLER_VERSION = "4.0"
	ZB_LAUNCH_ARG     = "-agentlib:zbNative"
	ZB_LAUNCH_OPTIONS = ZB_LAUNCH_ARG + " --"
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
	fmt.Println("ZombieBuddy Windows Installer v" + INSTALLER_VERSION)
	fmt.Println("-------------------------------")

	if runtime.GOOS != "windows" {
		fmt.Println("[!] Error: This installer is only designed for Windows.")
		waitForExit()
		return
	}

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
		fmt.Println("\nOkay, nothing changed.")
		waitForExit()
		return
	}

	switch result {
	case resultSucceeded:
		switch action {
		case "install":
			fmt.Println("\nInstallation complete! You can now start Steam and launch Project Zomboid.")
		case "uninstall":
			fmt.Println("\nUninstall complete! You can now start Steam and launch Project Zomboid.")
		}
	case resultCancelled:
		fmt.Println("\nNothing changed.")
	default:
		fmt.Printf("\n%s encountered errors. Please review the messages above.\n", displayAction(action))
	}
	waitForExit()
}

func waitForExit() {
	fmt.Println("Press Enter to exit...")
	bufio.NewReader(os.Stdin).ReadBytes('\n')
}

func promptAction() (string, error) {
	reader := bufio.NewReader(os.Stdin)
	return promptChoice(reader, promptChoiceConfig{
		title: "What can I do you for?",
		lines: []string{
			"  1) Install ZombieBuddy",
			"  2) Uninstall ZombieBuddy",
			"  3) Nothing, thanks!",
			"",
			"Any system changes will be shown for confirmation before they are applied.",
		},
		prompt:  "Choose 1, 2, or 3: ",
		eofErr:  "no action selected",
		invalid: "Please choose 1 for install, 2 for uninstall, or 3 to do nothing.",
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
		title:   "What launch mode should ZombieBuddy patch?",
		lines:   []string{"  1) Both", "  2) Normal Launch", "  3) Alternate Launch"},
		prompt:  "Choose 1, 2, or 3: ",
		eofErr:  "no launch mode selected",
		invalid: "Please choose 1, 2, or 3.",
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
		return patchTargets{}, fmt.Errorf("unknown launch mode %q", value)
	}
}

func promptNormalInstallTargets(reader *bufio.Reader) (patchTargets, error) {
	value, err := promptChoice(reader, promptChoiceConfig{
		title:   "How should Normal Launch be patched?",
		lines:   []string{"  1) Both", "  2) ProjectZomboid64.json", "  3) Steam launch options"},
		prompt:  "Choose 1, 2, or 3: ",
		eofErr:  "no normal launch patch selected",
		invalid: "Please choose 1, 2, or 3.",
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
		return patchTargets{}, fmt.Errorf("unknown normal launch target %q", value)
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
		fmt.Println("No system changes are needed.")
		return false, nil
	}

	fmt.Println()
	fmt.Println("ZombieBuddy will make these changes:")
	for _, line := range lines {
		fmt.Println("  - " + line)
	}
	fmt.Print("Continue? [y/N]: ")

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
		return "Installation"
	case "uninstall":
		return "Uninstall"
	case "nothing":
		return "Nothing"
	default:
		return "Operation"
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
		fmt.Printf("[!] Error selecting launch targets: %v\n", err)
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
		fmt.Printf("[!] Error reading confirmation: %v\n", err)
		return resultFailed
	}
	if !confirmed {
		fmt.Println("[-] Installation cancelled.")
		return resultCancelled
	}

	err = copyCoreFiles(paths.pz, paths.zb)
	if err != nil {
		fmt.Printf("[!] Error copying core files: %v\n", err)
		return resultFailed
	} else {
		fmt.Println("[.] Successfully installed zbNative.dll and ZombieBuddy.jar")
	}

	if targets.normalJSON {
		updatedJSON, err := patchJSONLauncher(paths.pz)
		if err != nil {
			fmt.Printf("[!] Error updating ProjectZomboid64.json: %v\n", err)
			return resultFailed
		}
		reportChange(updatedJSON, "Updated ProjectZomboid64.json for normal launcher mode", "ProjectZomboid64.json already contains ZombieBuddy agent.")
	}

	if targets.alternateBatch {
		updatedBatch, err := patchBatchFile(paths.pz)
		if err != nil {
			fmt.Printf("[!] Error updating ProjectZomboid64.bat: %v\n", err)
			return resultFailed
		}
		reportChange(updatedBatch, "Updated ProjectZomboid64.bat for alternative launcher mode", "ProjectZomboid64.bat already contains ZombieBuddy agent.")
	}

	if targets.steamLaunchOptions {
		err = updateLaunchOptions(paths.steam)
		if err != nil {
			fmt.Printf("[!] Error updating Steam launch options: %v\n", err)
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
		fmt.Printf("[!] Error planning uninstall: %v\n", err)
		return resultFailed
	}
	previewLines := uninstallPreview(plan)
	if len(previewLines) == 0 {
		fmt.Println("[-] Nothing to uninstall.")
		return resultCancelled
	}

	confirmed, err := confirmChanges(previewLines)
	if err != nil {
		fmt.Printf("[!] Error reading confirmation: %v\n", err)
		return resultFailed
	}
	if !confirmed {
		fmt.Println("[-] Uninstall cancelled.")
		return resultCancelled
	}

	if err := applyUninstallPlan(plan); err != nil {
		fmt.Printf("[!] Error applying uninstall changes: %v\n", err)
		return resultFailed
	}

	return resultSucceeded
}

func detectInstallPaths(includeZB bool) (installPaths, error) {
	maxLen := len("Steam")

	steamPath, err := detectSteamPath()
	if err != nil {
		return installPaths{}, fmt.Errorf("Error detecting Steam: %v", err)
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, "Steam", steamPath)

	pzPath, err := detectPZPath(steamPath)
	if err != nil {
		return installPaths{}, fmt.Errorf("Error detecting Project Zomboid: %v", err)
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, "PZ", pzPath)

	paths := installPaths{steam: steamPath, pz: pzPath}
	if includeZB {
		zbPath, err := detectZBPath(steamPath)
		if err != nil {
			return installPaths{}, fmt.Errorf("Error detecting ZombieBuddy mod: %v", err)
		}
		fmt.Printf("[.] %-*s is at %s\n", maxLen, "ZB", zbPath)
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
		return uninstallPlan{}, fmt.Errorf("checking ProjectZomboid64.json: %v", err)
	}
	if hasJSON {
		plan.jsonLauncherPath = jsonPath
	}

	batchPath := filepath.Join(pzPath, "ProjectZomboid64.bat")
	hasBatch, err := batchFileHasZombieBuddy(batchPath)
	if err != nil {
		return uninstallPlan{}, fmt.Errorf("checking ProjectZomboid64.bat: %v", err)
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
			return fmt.Errorf("updating ProjectZomboid64.json: %v", err)
		}
		reportChange(updated, fmt.Sprintf("Removed \"%s\" from ProjectZomboid64.json", ZB_LAUNCH_ARG), "")
	}

	if plan.batchFilePath != "" {
		updated, err := updateBatchJavaOptions(plan.batchFilePath, false)
		if err != nil {
			return fmt.Errorf("updating ProjectZomboid64.bat: %v", err)
		}
		reportChange(updated, fmt.Sprintf("Removed \"%s\" from ProjectZomboid64.bat", ZB_LAUNCH_ARG), "")
	}

	if err := removeCoreFiles(plan.coreFilePaths); err != nil {
		return fmt.Errorf("removing core files: %v", err)
	}

	if err := removeLaunchOptions(plan.steamConfigPaths); err != nil {
		return fmt.Errorf("removing Steam launch options: %v", err)
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
		fmt.Sprintf("copy \"%s\\zbNative.dll\"    to \"%s\\\"", "ZB", "PZ"),
		fmt.Sprintf("copy \"%s\\ZombieBuddy.jar\" to \"%s\\\"", "ZB", "PZ"),
	}
	if targets.normalJSON {
		lines = append(lines, fmt.Sprintf("add \"%s\" to \"%s\"", ZB_LAUNCH_ARG, "ProjectZomboid64.json"))
	}
	if targets.alternateBatch {
		lines = append(lines, fmt.Sprintf("add \"%s\" to \"%s\"", ZB_LAUNCH_ARG, "ProjectZomboid64.bat"))
	}
	if targets.steamLaunchOptions {
		lines = append(lines, fmt.Sprintf("add \"%s\" to PZ Steam launch options", ZB_LAUNCH_OPTIONS))
	}
	return lines
}

func uninstallPreview(plan uninstallPlan) []string {
	var lines []string
	if plan.jsonLauncherPath != "" {
		lines = append(lines, fmt.Sprintf("remove \"%s\" from \"%s\"", ZB_LAUNCH_ARG, plan.jsonLauncherPath))
	}
	if plan.batchFilePath != "" {
		lines = append(lines, fmt.Sprintf("remove \"%s\" from \"%s\"", ZB_LAUNCH_ARG, plan.batchFilePath))
	}
	if plan.steamConfigPaths != nil {
		lines = append(lines, fmt.Sprintf("remove \"%s\" from PZ Steam launch options", ZB_LAUNCH_ARG))
	}
	for _, path := range plan.coreFilePaths {
		lines = append(lines, fmt.Sprintf("delete \"%s\"", path))
	}
	return lines
}

func waitForSteamToClose(operation string) {
	steamWasRunning := false
	for isSteamRunning() {
		steamWasRunning = true
		fmt.Println("\n[!] Steam is currently running.")
		fmt.Println("    Please close Steam completely before continuing.")
		fmt.Println("    (Right-click Steam in system tray -> Exit)")
		fmt.Println("\nPress Enter after closing Steam...")
		bufio.NewReader(os.Stdin).ReadBytes('\n')
	}
	if steamWasRunning {
		fmt.Printf("[.] Steam is now closed. Continuing %s...\n\n", operation)
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

	return "", fmt.Errorf("could not find Project Zomboid installation")
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

	return "", fmt.Errorf("Make sure you've subscribed to https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853")
}

func findAppInLibraries(libraryVDFPath string, subPath ...string) (string, error) {
	m, err := parseVDFMap(libraryVDFPath)
	if err != nil {
		return "", err
	}

	libraryfolders, ok := m["libraryfolders"].(map[string]interface{})
	if !ok {
		return "", fmt.Errorf("invalid libraryfolders.vdf")
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

	return "", fmt.Errorf("app not found in any Steam library")
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
			return fmt.Errorf("could not find %s source", filename)
		}

		dstPath := filepath.Join(pzPath, filename)
		fmt.Printf("[.] copying \"%s\" to \"%s\"\n", srcPath, dstPath)
		err := copyFile(srcPath, dstPath)
		if err != nil {
			return fmt.Errorf("failed to copy %s: %v", filename, err)
		}
	}

	return nil
}

func removeCoreFiles(paths []string) error {
	for _, path := range paths {
		err := os.Remove(path)
		if err == nil {
			fmt.Printf("[.] Removed %s\n", path)
			continue
		}
		if os.IsNotExist(err) {
			continue
		}
		return fmt.Errorf("failed to remove %s: %v", path, err)
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
		return fmt.Errorf("no user configurations found to update")
	}
	return nil
}

func removeLaunchOptions(localConfigPaths []string) error {
	if len(localConfigPaths) == 0 {
		return nil
	}

	waitForSteamToClose("launch option update")
	for _, localConfigPath := range localConfigPaths {
		updated, err := unpatchVDF(localConfigPath, false)
		if err != nil {
			return fmt.Errorf("failed to remove launch options from %s: %v", localConfigPath, err)
		}
		if updated {
			fmt.Printf("[.] Removed launch options from %s\n", localConfigPath)
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
					fmt.Printf("[?] skipping Steam launch options for user %s: %v\n", entry.Name(), err)
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
		return false, fmt.Errorf("could not create Project Zomboid launch config")
	}

	currentOptions := launchOptionsFromConfig(pz)

	newOptions := ZB_LAUNCH_OPTIONS
	if hasZombieBuddyLaunchOptions(currentOptions) {
		fmt.Println("[-] Launch options already contain ZombieBuddy agent.")
		return false, nil
	}

	waitForSteamToClose("launch option update")
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
		waitForSteamToClose("launch option update")
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

	apps, err := navigateMap(m, "Software", "Valve", "Steam", "Apps")
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
	m, err := vdf.ParseAutoFile(path)
	if err != nil {
		return nil, err
	}
	normalized, ok := normalizeVDFValue(m).(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("invalid VDF root")
	}
	return normalized, nil
}

func normalizeVDFValue(value interface{}) interface{} {
	switch v := value.(type) {
	case vdf.Map:
		m := make(map[string]interface{}, len(v))
		for key, child := range v {
			m[key] = normalizeVDFValue(child)
		}
		return m
	case map[string]interface{}:
		m := make(map[string]interface{}, len(v))
		for key, child := range v {
			m[key] = normalizeVDFValue(child)
		}
		return m
	default:
		return value
	}
}

func navigateMap(m map[string]interface{}, path ...string) (map[string]interface{}, error) {
	current := m
	for _, key := range path {
		found := false
		for k, v := range current {
			if strings.EqualFold(k, key) {
				next, ok := v.(map[string]interface{})
				if !ok {
					return nil, fmt.Errorf("key %s is not a map", k)
				}
				current = next
				found = true
				break
			}
		}
		if !found {
			return nil, fmt.Errorf("key %s not found", key)
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
