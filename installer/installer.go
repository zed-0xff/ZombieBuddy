package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/andygrunwald/vdf"
	"golang.org/x/sys/windows/registry"
)

const (
	PZ_APP_ID = "108600"
	ZB_MOD_ID = "3619862853"
)

func main() {
	success := install()

	if success {
		fmt.Println("\nInstallation complete! You can now start Steam and launch Project Zomboid.")
	} else {
		fmt.Println("\nInstallation encountered errors. Please review the messages above.")
	}
	fmt.Println("Press Enter to exit...")
	bufio.NewReader(os.Stdin).ReadBytes('\n')
}

func isSteamRunning() bool {
	cmd := exec.Command("tasklist", "/FI", "IMAGENAME eq steam.exe", "/NH")
	output, err := cmd.Output()
	if err != nil {
		return false
	}
	return strings.Contains(strings.ToLower(string(output)), "steam.exe")
}

func install() bool {
	fmt.Println("ZombieBuddy Installer (Windows)")
	fmt.Println("-------------------------------")

	if runtime.GOOS != "windows" {
		fmt.Println("[!] Error: This installer is only designed for Windows.")
		return false
	}

	// Check if Steam is running
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
		fmt.Println("[.] Steam is now closed. Continuing installation...\n")
	}

	maxLen := len("Steam")

	steamPath, err := detectSteamPath()
	if err != nil {
		fmt.Printf("[!] Error detecting Steam: %v\n", err)
		return false
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, "Steam", steamPath)

	pzPath, err := detectPZPath(steamPath)
	if err != nil {
		fmt.Printf("[!] Error detecting Project Zomboid: %v\n", err)
		return false
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, "PZ", pzPath)

	zbPath, err := detectZBPath(steamPath)
	if err != nil {
		fmt.Printf("[!] Error detecting ZombieBuddy mod: %v\n", err)
		return false
	}
	fmt.Printf("[.] %-*s is at %s\n", maxLen, "ZB", zbPath)

	err = copyCoreFiles(pzPath, zbPath)
	if err != nil {
		fmt.Printf("[!] Error copying core files: %v\n", err)
		return false
	} else {
		fmt.Println("[.] Successfully installed zbNative.dll and ZombieBuddy.jar")
	}

	err = updateLaunchOptions(steamPath)
	if err != nil {
		fmt.Printf("[!] Error updating Steam launch options: %v\n", err)
		return false
	}

	return true
}

func detectSteamPath() (string, error) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Valve\Steam`, registry.QUERY_VALUE)
	if err != nil {
		k, err = registry.OpenKey(registry.LOCAL_MACHINE, `Software\Valve\Steam`, registry.QUERY_VALUE)
		if err != nil {
			k, err = registry.OpenKey(registry.LOCAL_MACHINE, `Software\WOW6432Node\Valve\Steam`, registry.QUERY_VALUE)
			if err != nil {
				return "", fmt.Errorf("could not find Steam registry key")
			}
		}
	}
	defer k.Close()

	s, _, err := k.GetStringValue("SteamPath")
	if err != nil {
		return "", err
	}
	return filepath.Clean(s), nil
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
	f, err := os.Open(libraryVDFPath)
	if err != nil {
		return "", err
	}
	defer f.Close()

	p := vdf.NewParser(f)
	m, err := p.Parse()
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
			// New simplified path
			sources = append(sources, filepath.Join(zbPath, "mods", "ZombieBuddy", "libs", filename))
			// Old path (for backwards compatibility)
			sources = append(sources, filepath.Join(zbPath, "mods", "ZombieBuddy", "42", "media", "java", "client", "build", "libs", filename))
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

		fmt.Printf("[.] copying %s\n", srcPath)
		destPath := filepath.Join(pzPath, filename)
		err := copyFile(srcPath, destPath)
		if err != nil {
			return fmt.Errorf("failed to copy %s: %v", filename, err)
		}
	}

	return nil
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

func patchVDF(path string) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()

	p := vdf.NewParser(f)
	m, err := p.Parse()
	if err != nil {
		return false, err
	}

	// Navigate to UserLocalConfigStore -> Software -> Valve -> Steam -> Apps
	apps, err := navigateMap(m, "UserLocalConfigStore", "Software", "Valve", "Steam", "Apps")
	if err != nil {
		return false, err
	}

	var pz map[string]interface{}
	for k, v := range apps {
		if strings.EqualFold(k, PZ_APP_ID) {
			pz, _ = v.(map[string]interface{})
			break
		}
	}

	if pz == nil {
		pz = make(map[string]interface{})
		apps[PZ_APP_ID] = pz
	}

	var currentOptions string
	for k, v := range pz {
		if strings.EqualFold(k, "LaunchOptions") {
			currentOptions, _ = v.(string)
			break
		}
	}

	newOptions := "-agentlib:zbNative --"
	if currentOptions == newOptions || strings.HasPrefix(currentOptions, newOptions+" ") {
		fmt.Println("[-] Launch options already contain ZombieBuddy agent.")
		return false, nil
	}

	return true, manualPatchVDF(path, currentOptions, newOptions)
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
