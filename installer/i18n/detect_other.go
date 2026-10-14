//go:build !windows

package i18n

func detectSystemLanguage() Lang {
	return EN
}
