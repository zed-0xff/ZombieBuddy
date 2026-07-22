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

// allLanguages lists the supported languages in canonical display order.
var allLanguages = []Lang{EN, ZH, FR, ES, RU, AR}

// languageNativeName returns the native name of a language for the menu.
func languageNativeName(lang Lang) string {
	if name, ok := languageNativeNames[lang]; ok {
		return name
	}
	return string(lang)
}

var languageNativeNames = map[Lang]string{
	EN: "English",
	ZH: "中文",
	FR: "Français",
	ES: "Español",
	RU: "Русский",
	AR: "العربية",
}

// current holds the active language. Defaults to English.
var current Lang = EN

// Current returns the active language code.
func Current() Lang { return current }

// Translate returns the translation for key in the current language.
// Falls back to English if the key is missing.
func Translate(key string) string {
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

	// Build ordered list: detected first, then remaining in canonical order.
	order := make([]Lang, 0, len(allLanguages))
	order = append(order, detected)
	for _, lang := range allLanguages {
		if lang != detected {
			order = append(order, lang)
		}
	}

	for i, lang := range order {
		tag := ""
		if lang == detected {
			tag = " (auto-detected)"
		}
		fmt.Printf("  %d) %s%s\n", i+1, languageNativeName(lang), tag)
	}

	fmt.Println()
	fmt.Print("Choose or press Enter for auto-detected: ")

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		return
	}
	choice := strings.TrimSpace(line)
	if choice == "" {
		return // keep detected
	}

	var idx int
	if _, parseErr := fmt.Sscanf(choice, "%d", &idx); parseErr != nil || idx < 1 || idx > len(order) {
		return // keep detected
	}
	current = order[idx-1]
}
