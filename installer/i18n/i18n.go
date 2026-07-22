package i18n

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

// Lang is an ISO 639-1 language code.
type Lang string

const (
	EN Lang = "en"
	ZH Lang = "zh"
	FR Lang = "fr"
	ES Lang = "es"
	RU Lang = "ru"
	AR Lang = "ar"
)

// current holds the active language. Defaults to English.
var current Lang = EN

// Current returns the active language code.
func Current() Lang { return current }

// Tr returns the translation for key in the current language.
// Falls back to English if the key is missing.
func Tr(key string) string {
	if msg, ok := messages[current][key]; ok {
		return msg
	}
	if msg, ok := messages[EN][key]; ok {
		return msg
	}
	return key
}

// messages aggregates all per-language maps. Each per-language file
// contributes its own var (messagesEN, messagesZH, …) via init order.
var messages = map[Lang]map[string]string{
	EN: messagesEN,
	ZH: messagesZH,
	FR: messagesFR,
	ES: messagesES,
	RU: messagesRU,
	AR: messagesAR,
}

// PromptLanguage auto‑detects the system language, then shows a menu so the
// user can override. Pressing Enter accepts the auto‑detected language.
func PromptLanguage() {
	detected := detectSystemLanguage()
	current = detected

	fmt.Println("Please select language / 请选择语言 / Choisir la langue / Seleccionar idioma / Выберите язык / اختر اللغة:")
	fmt.Println("  1) English    2) 中文      3) Français")
	fmt.Println("  4) Español    5) Русский   6) العربية")
	fmt.Printf("Choose or press Enter for auto-detected [%s]: ", detected)

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		return
	}
	switch strings.TrimSpace(line) {
	case "1":
		current = EN
	case "2":
		current = ZH
	case "3":
		current = FR
	case "4":
		current = ES
	case "5":
		current = RU
	case "6":
		current = AR
	}
}
