//go:build windows

package i18n

import "golang.org/x/sys/windows"

// detectSystemLanguage returns the best-matching Lang for the current
// Windows UI language. Traditional Chinese variants fall back to ZH
// (Simplified Chinese). Unsupported languages fall back to EN.
func detectSystemLanguage() Lang {
	langID, err := windows.GetUserDefaultUILanguage()
	if err != nil {
		return EN
	}

	switch langID {
	// ── Chinese ──────────────────────────────────────────────
	case 0x0804: // zh-CN  (Simplified)
		return ZH
	case 0x0004: // zh-Hans
		return ZH
	case 0x1004: // zh-SG
		return ZH
	case 0x0404: // zh-TW  (Traditional → Simplified)
		return ZH
	case 0x0c04: // zh-HK  (Traditional → Simplified)
		return ZH
	case 0x1404: // zh-MO  (Traditional → Simplified)
		return ZH
	case 0x7c04: // zh-Hant
		return ZH

	// ── French ───────────────────────────────────────────────
	case 0x040c: // fr-FR
		return FR
	case 0x080c: // fr-BE
		return FR
	case 0x0c0c: // fr-CA
		return FR
	case 0x100c: // fr-CH
		return FR
	case 0x140c: // fr-LU
		return FR
	case 0x180c: // fr-MC
		return FR

	// ── Spanish ──────────────────────────────────────────────
	case 0x040a, 0x080a, 0x0c0a, 0x100a, 0x140a,
		0x180a, 0x1c0a, 0x200a, 0x240a, 0x280a,
		0x2c0a, 0x300a, 0x340a, 0x380a, 0x3c0a,
		0x400a, 0x440a, 0x480a, 0x4c0a, 0x500a:
		return ES

	// ── Russian ──────────────────────────────────────────────
	case 0x0419: // ru-RU
		return RU
	case 0x0819: // ru-MD
		return RU

	// ── Arabic ───────────────────────────────────────────────
	case 0x0401, 0x0801, 0x0c01, 0x1001, 0x1401,
		0x1801, 0x1c01, 0x2001, 0x2401, 0x2801,
		0x2c01, 0x3001, 0x3401, 0x3801, 0x3c01,
		0x4001:
		return AR

	default:
		return EN
	}
}
