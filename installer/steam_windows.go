//go:build windows

package main

import (
	"fmt"
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
