//go:build !windows

package main

import (
	"fmt"

	"github.com/zed-0xff/zombie_buddy/installer/i18n"
)

func detectSteamPath() (string, error) {
	return "", fmt.Errorf("%s", i18n.Translate("steam.registry_windows_only"))
}
