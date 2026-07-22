//go:build windows

package main

import "golang.org/x/sys/windows"

func init() {
	// Set console output to UTF-8 so that non-Latin scripts (such as Arabic,
	// Chinese, Cyrillic) render correctly. On older console hosts that use a
	// font without the required glyphs (e.g. SimSun for Arabic), some
	// characters may still appear as boxes — using Windows Terminal is
	// recommended in that case.
	_ = windows.SetConsoleOutputCP(65001)   // CP_UTF8
	_ = windows.SetConsoleCP(65001)         // also set input CP for consistency
}
