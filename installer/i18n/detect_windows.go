//go:build windows

package i18n

import "syscall"

// detectSystemLanguage returns the best-matching Lang for the current
// Windows UI language.
func detectSystemLanguage() Lang {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GetUserDefaultUILanguage")
	langID, _, _ := proc.Call()
	return langIDToLang(uint16(langID))
}
