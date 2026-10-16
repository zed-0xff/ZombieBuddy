//go:build windows

package main

import (
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows/registry"
)

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

func detectSteamAppInstallPath(appID string) (string, error) {
	keyPath := `Software\Microsoft\Windows\CurrentVersion\Uninstall\Steam App ` + appID
	type registryLocation struct {
		root   registry.Key
		access uint32
	}

	locations := []registryLocation{
		{registry.CURRENT_USER, registry.QUERY_VALUE},
		{registry.CURRENT_USER, registry.QUERY_VALUE | registry.WOW64_32KEY},
		{registry.CURRENT_USER, registry.QUERY_VALUE | registry.WOW64_64KEY},
		{registry.LOCAL_MACHINE, registry.QUERY_VALUE},
		{registry.LOCAL_MACHINE, registry.QUERY_VALUE | registry.WOW64_32KEY},
		{registry.LOCAL_MACHINE, registry.QUERY_VALUE | registry.WOW64_64KEY},
	}

	for _, location := range locations {
		k, err := registry.OpenKey(location.root, keyPath, location.access)
		if err != nil {
			continue
		}
		installLocation, _, valueErr := k.GetStringValue("InstallLocation")
		k.Close()
		if valueErr != nil || installLocation == "" {
			continue
		}

		path := filepath.Clean(installLocation)
		if _, err := os.Stat(path); err == nil {
			return path, nil
		}
	}

	return "", fmt.Errorf("could not find Steam app %s in registry", appID)
}
