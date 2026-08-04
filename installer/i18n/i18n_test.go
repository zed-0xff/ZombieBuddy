package i18n

import "testing"

func TestTranslateHit(t *testing.T) {
	defer func(prev Lang) { current = prev }(current)
	current = ZH

	got := Translate("app.title")
	want := messagesZH["app.title"]
	if got != want {
		t.Fatalf("Translate(%q) = %q, want %q", "app.title", got, want)
	}
}

func TestTranslateFallbackToEnglish(t *testing.T) {
	defer func(prev Lang) { current = prev }(current)

	// A key that exists only in the English catalog must fall back to it.
	const key = "test.english_only"
	messagesEN[key] = "fallback value"
	defer delete(messagesEN, key)

	current = ZH
	if got := Translate(key); got != "fallback value" {
		t.Fatalf("Translate(%q) = %q, want %q", key, got, "fallback value")
	}
}

func TestTranslateUnknownKeyReturnsKey(t *testing.T) {
	defer func(prev Lang) { current = prev }(current)
	current = EN

	const key = "no.such.key"
	if got := Translate(key); got != key {
		t.Fatalf("Translate(%q) = %q, want the key itself", key, got)
	}
}

func TestCatalogKeySetsMatchEnglish(t *testing.T) {
	for _, lang := range allLanguages {
		t.Run(string(lang), func(t *testing.T) {
			for key := range messagesEN {
				if _, ok := messages[lang][key]; !ok {
					t.Errorf("catalog %s is missing key %q", lang, key)
				}
			}
			for key := range messages[lang] {
				if _, ok := messagesEN[key]; !ok {
					t.Errorf("catalog %s has unexpected extra key %q", lang, key)
				}
			}
		})
	}
}

func TestLangIDToLang(t *testing.T) {
	tests := []struct {
		id   uint16
		want Lang
	}{
		// Chinese variants (Traditional fall back to Simplified).
		{0x0804, ZH}, {0x0004, ZH}, {0x1004, ZH},
		{0x0404, ZH}, {0x0c04, ZH}, {0x1404, ZH}, {0x7c04, ZH},
		// French.
		{0x040c, FR}, {0x080c, FR}, {0x0c0c, FR}, {0x100c, FR}, {0x140c, FR}, {0x180c, FR},
		// Spanish (all variants).
		{0x040a, ES}, {0x0c0a, ES}, {0x2c0a, ES}, {0x500a, ES},
		// Russian.
		{0x0419, RU}, {0x0819, RU},
		// Arabic (all variants).
		{0x0401, AR}, {0x1c01, AR}, {0x4001, AR},
		// Unsupported → English.
		{0x0409, EN}, {0x0000, EN}, {0xffff, EN},
	}

	for _, tt := range tests {
		if got := langIDToLang(tt.id); got != tt.want {
			t.Errorf("langIDToLang(0x%04x) = %q, want %q", tt.id, got, tt.want)
		}
	}
}
