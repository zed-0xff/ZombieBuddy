//go:build !windows

package main

import "fmt"

func detectSteamPath() (string, error) {
	return "", fmt.Errorf("Steam registry lookup is only supported on Windows")
}

func detectSteamAppInstallPath(appID string) (string, error) {
	return "", fmt.Errorf("Steam app registry lookup is only supported on Windows")
}
