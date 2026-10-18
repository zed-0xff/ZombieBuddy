package main

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFindAppInLibraries(t *testing.T) {
	tests := []struct {
		name    string
		inLib   int // which library gets the target subpath (0 or 1); -1 = neither
		wantErr bool
	}{
		{"found in first library", 0, false},
		{"found in second library only", 1, false},
		{"not found in any library", -1, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tmpDir := t.TempDir()
			libs := []string{filepath.Join(tmpDir, "lib0"), filepath.Join(tmpDir, "lib1")}
			for _, lib := range libs {
				if err := os.MkdirAll(lib, 0755); err != nil {
					t.Fatal(err)
				}
			}

			if tt.inLib >= 0 {
				if err := os.MkdirAll(filepath.Join(libs[tt.inLib], "steamapps", "common", "ProjectZomboid"), 0755); err != nil {
					t.Fatal(err)
				}
			}

			vdfContent := "\"libraryfolders\"\n{\n" +
				"\t\"0\"\n\t{\n\t\t\"path\"\t\t\"" + strings.ReplaceAll(libs[0], "\\", "\\\\") + "\"\n\t}\n" +
				"\t\"1\"\n\t{\n\t\t\"path\"\t\t\"" + strings.ReplaceAll(libs[1], "\\", "\\\\") + "\"\n\t}\n" +
				"}\n"
			vdfPath := filepath.Join(tmpDir, "libraryfolders.vdf")
			if err := os.WriteFile(vdfPath, []byte(vdfContent), 0644); err != nil {
				t.Fatal(err)
			}

			got, err := findAppInLibraries(vdfPath, "common", "ProjectZomboid")
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected error, got path %q", got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			want := filepath.Join(libs[tt.inLib], "steamapps", "common", "ProjectZomboid")
			if got != want {
				t.Errorf("got %q, want %q", got, want)
			}
		})
	}
}

func TestStripZombieBuddyLaunchOptions(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    string
		changed bool
	}{
		{
			name:    "plain agent option",
			input:   "-agentlib:zbNative",
			want:    "",
			changed: true,
		},
		{
			name:    "agent option without separator",
			input:   "-agentlib:zbNative--",
			want:    "",
			changed: true,
		},
		{
			name:    "agent option with separator",
			input:   "-agentlib:zbNative --",
			want:    "",
			changed: true,
		},
		{
			name:    "agent option with spaces before separator",
			input:   "-agentlib:zbNative   --",
			want:    "",
			changed: true,
		},
		{
			name:    "agent option with separator and game args",
			input:   "-agentlib:zbNative -- -debug",
			want:    "-debug",
			changed: true,
		},
		{
			name:    "agent option with arguments",
			input:   "-agentlib:zbNative=policy=deny-new -- -debug",
			want:    "-debug",
			changed: true,
		},
		{
			name:    "agent option preserves unrelated prefix and suffix",
			input:   "-nosteam -agentlib:zbNative -- -debug",
			want:    "-nosteam -debug",
			changed: true,
		},
		{
			name:    "no agent option",
			input:   "-debug",
			want:    "-debug",
			changed: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, changed := stripZombieBuddyLaunchOptions(tt.input)
			if got != tt.want {
				t.Fatalf("stripZombieBuddyLaunchOptions(%q) = %q, want %q", tt.input, got, tt.want)
			}
			if changed != tt.changed {
				t.Fatalf("changed = %v, want %v", changed, tt.changed)
			}
		})
	}
}

func TestPatchJSONLauncherFixtures(t *testing.T) {
	for _, version := range launcherFixtureVersions() {
		t.Run(version, func(t *testing.T) {
			pzPath := copyLauncherFixture(t, version, "ProjectZomboid64.json", "vanilla")

			changed, err := patchJSONLauncher(pzPath)
			if err != nil {
				t.Fatalf("patchJSONLauncher() error = %v", err)
			}
			if !changed {
				t.Fatal("patchJSONLauncher() changed = false, want true")
			}
			assertFileMatchesFixture(t, filepath.Join(pzPath, "ProjectZomboid64.json"), version, "ProjectZomboid64.json", "patched")

			changed, err = unpatchJSONLauncher(pzPath)
			if err != nil {
				t.Fatalf("unpatchJSONLauncher() error = %v", err)
			}
			if !changed {
				t.Fatal("unpatchJSONLauncher() changed = false, want true")
			}
			assertFileMatchesFixture(t, filepath.Join(pzPath, "ProjectZomboid64.json"), version, "ProjectZomboid64.json", "vanilla")
		})
	}
}

func TestPatchBatchLauncherFixtures(t *testing.T) {
	for _, version := range launcherFixtureVersions() {
		t.Run(version, func(t *testing.T) {
			pzPath := copyLauncherFixture(t, version, "ProjectZomboid64.bat", "vanilla")

			changed, err := patchBatchFile(pzPath)
			if err != nil {
				t.Fatalf("patchBatchFile() error = %v", err)
			}
			if !changed {
				t.Fatal("patchBatchFile() changed = false, want true")
			}
			assertFileMatchesFixture(t, filepath.Join(pzPath, "ProjectZomboid64.bat"), version, "ProjectZomboid64.bat", "patched")

			changed, err = unpatchBatchFile(pzPath)
			if err != nil {
				t.Fatalf("unpatchBatchFile() error = %v", err)
			}
			if !changed {
				t.Fatal("unpatchBatchFile() changed = false, want true")
			}
			assertFileMatchesFixture(t, filepath.Join(pzPath, "ProjectZomboid64.bat"), version, "ProjectZomboid64.bat", "vanilla")
		})
	}
}

func launcherFixtureVersions() []string {
	return []string{"41.78", "42.12", "42.17"}
}

func copyLauncherFixture(t *testing.T, version string, filename string, state string) string {
	t.Helper()

	pzPath := t.TempDir()
	input := readLauncherFixture(t, version, filename, state)
	if err := os.WriteFile(filepath.Join(pzPath, filename), input, 0644); err != nil {
		t.Fatalf("WriteFile(%s) error = %v", filename, err)
	}
	return pzPath
}

func assertFileMatchesFixture(t *testing.T, path string, version string, filename string, state string) {
	t.Helper()

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile(%s) error = %v", path, err)
	}
	want := readLauncherFixture(t, version, filename, state)
	if normalizeText(got) != normalizeText(want) {
		t.Fatalf("%s does not match %s %s fixture\n%s", filename, version, state, diffLines(want, got))
	}
}

func readLauncherFixture(t *testing.T, version string, filename string, state string) []byte {
	t.Helper()

	path := filepath.Join("testdata", "launchers", version, state, filename)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile(%s) error = %v", path, err)
	}
	return data
}

func diffLines(want []byte, got []byte) string {
	wantLines := normalizedLines(want)
	gotLines := normalizedLines(got)

	prefix := 0
	for prefix < len(wantLines) && prefix < len(gotLines) && wantLines[prefix] == gotLines[prefix] {
		prefix++
	}

	wantEnd := len(wantLines)
	gotEnd := len(gotLines)
	for wantEnd > prefix && gotEnd > prefix && wantLines[wantEnd-1] == gotLines[gotEnd-1] {
		wantEnd--
		gotEnd--
	}

	var b strings.Builder
	b.WriteString("--- want\n")
	b.WriteString("+++ got\n")
	b.WriteString("@@ ")
	b.WriteString(lineRange(prefix+1, wantEnd-prefix))
	b.WriteString(" ")
	b.WriteString(lineRange(prefix+1, gotEnd-prefix))
	b.WriteString(" @@\n")

	for _, line := range wantLines[prefix:wantEnd] {
		b.WriteString("-")
		b.WriteString(line)
		b.WriteString("\n")
	}
	for _, line := range gotLines[prefix:gotEnd] {
		b.WriteString("+")
		b.WriteString(line)
		b.WriteString("\n")
	}
	return b.String()
}

func normalizedLines(value []byte) []string {
	return strings.Split(normalizeText(value), "\n")
}

func normalizeText(value []byte) string {
	text := strings.ReplaceAll(string(value), "\r\n", "\n")
	return strings.ReplaceAll(text, "\r", "\n")
}

func lineRange(start int, count int) string {
	if count <= 1 {
		return fmt.Sprintf("%d", start)
	}
	return fmt.Sprintf("%d,%d", start, count)
}

func TestDetectZBPath(t *testing.T) {
	tests := []struct {
		name     string
		copies   []int
		wantLib  int
		noPZPath bool
		noVDF    bool
	}{
		{name: "game library wins over default and other copies", copies: []int{0, 1, 2}, wantLib: 1},
		{name: "game library works without libraryfolders", copies: []int{1}, wantLib: 1, noVDF: true},
		{name: "missing game copy falls back to default", copies: []int{0, 2}, wantLib: 0},
		{name: "missing game and default copies falls back to other library", copies: []int{2}, wantLib: 2},
		{name: "empty game path falls back to default", copies: []int{0, 1}, wantLib: 0, noPZPath: true},
		{name: "empty game path falls back to other library", copies: []int{2}, wantLib: 2, noPZPath: true},
		{name: "no subscribed copy", wantLib: -1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tmpDir := t.TempDir()
			libs := []string{
				filepath.Join(tmpDir, "Steam"),
				filepath.Join(tmpDir, "Game Library"),
				filepath.Join(tmpDir, "Other Library"),
			}
			for _, lib := range libs {
				if err := os.MkdirAll(filepath.Join(lib, "steamapps"), 0755); err != nil {
					t.Fatal(err)
				}
			}
			// A custom game folder still has steamapps/common as its parents.
			pzPath := filepath.Join(libs[1], "steamapps", "common", "ProjectZomboid Custom")
			if err := os.MkdirAll(pzPath, 0755); err != nil {
				t.Fatal(err)
			}
			workshopPath := func(lib int) string {
				return filepath.Join(libs[lib], "steamapps", "workshop", "content", PZ_APP_ID, ZB_MOD_ID)
			}
			for _, lib := range tt.copies {
				if err := os.MkdirAll(workshopPath(lib), 0755); err != nil {
					t.Fatal(err)
				}
			}
			if !tt.noVDF {
				vdfContent := "\"libraryfolders\"\n{\n"
				for i, lib := range libs {
					vdfContent += fmt.Sprintf("\"%d\" { \"path\" \"%s\" }\n", i, filepath.ToSlash(lib))
				}
				vdfContent += "}\n"
				if err := os.WriteFile(filepath.Join(libs[0], "steamapps", "libraryfolders.vdf"), []byte(vdfContent), 0644); err != nil {
					t.Fatal(err)
				}
			}
			if tt.noPZPath {
				pzPath = ""
			}
			got, err := detectZBPath(libs[0], pzPath)
			if tt.wantLib < 0 {
				if err == nil {
					t.Fatalf("expected an error, got path %q", got)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if want := workshopPath(tt.wantLib); got != want {
				t.Fatalf("got %q, want %q", got, want)
			}
		})
	}
}

func TestAddZombieBuddyLaunchOptions(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{"empty", "", "-agentlib:zbNative --"},
		{"blank", "   ", "-agentlib:zbNative --"},
		{"preserves existing options", "-cachedir=G:\\Cache", "-agentlib:zbNative -- -cachedir=G:\\Cache"},
		{"preserves multiple options", "-nosteam -debug", "-agentlib:zbNative -- -nosteam -debug"},
		{"after %command%", "ENV=1 %command% -debug", "ENV=1 %command% -agentlib:zbNative -- -debug"},
		{"bare %command%", "%command%", "%command% -agentlib:zbNative --"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := addZombieBuddyLaunchOptions(tt.input)
			if got != tt.want {
				t.Fatalf("addZombieBuddyLaunchOptions(%q) = %q, want %q", tt.input, got, tt.want)
			}
			stripped, changed := stripZombieBuddyLaunchOptions(got)
			if !changed || stripped != strings.Join(strings.Fields(tt.input), " ") {
				t.Fatalf("strip(%q) = %q, %v; want %q", got, stripped, changed, strings.Join(strings.Fields(tt.input), " "))
			}
		})
	}
}

func TestPatchVDFPreservesLaunchOptions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "localconfig.vdf")
	vdfText := "\"UserLocalConfigStore\"\n{\n\t\"Software\"\n\t{\n\t\t\"Valve\"\n\t\t{\n\t\t\t\"Steam\"\n\t\t\t{\n\t\t\t\t\"Apps\"\n\t\t\t\t{\n" +
		"\t\t\t\t\t\"" + PZ_APP_ID + "\"\n\t\t\t\t\t{\n" +
		"\t\t\t\t\t\t\"LaunchOptions\"\t\t\"-cachedir=\\\"G:\\\\Some Dir\\\" -debug\"\n" +
		"\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t}\n\t}\n}\n"
	if err := os.WriteFile(path, []byte(vdfText), 0644); err != nil {
		t.Fatal(err)
	}
	if err := manualPatchVDF(path, "", addZombieBuddyLaunchOptions(`-cachedir="G:\Some Dir" -debug`)); err != nil {
		t.Fatal(err)
	}
	got, err := readPZLaunchOptions(path)
	if err != nil {
		t.Fatal(err)
	}
	want := `-agentlib:zbNative -- -cachedir="G:\Some Dir" -debug`
	if got != want {
		t.Fatalf("launch options = %q, want %q", got, want)
	}
}

func TestDetectZBPathBeta(t *testing.T) {
	tmpDir := t.TempDir()
	steam := filepath.Join(tmpDir, "Steam")
	pzPath := filepath.Join(steam, "steamapps", "common", "ProjectZomboid")
	if err := os.MkdirAll(pzPath, 0755); err != nil {
		t.Fatal(err)
	}
	workshop := func(id string) string {
		return filepath.Join(steam, "steamapps", "workshop", "content", PZ_APP_ID, id)
	}

	if _, err := detectZBPath(steam, pzPath); err == nil {
		t.Fatal("expected an error with no subscribed copy")
	}

	if err := os.MkdirAll(workshop(ZB_BETA_MOD_ID), 0755); err != nil {
		t.Fatal(err)
	}
	got, err := detectZBPath(steam, pzPath)
	if err != nil || got != workshop(ZB_BETA_MOD_ID) {
		t.Fatalf("beta only: got %q, %v; want %q", got, err, workshop(ZB_BETA_MOD_ID))
	}

	if err := os.MkdirAll(workshop(ZB_MOD_ID), 0755); err != nil {
		t.Fatal(err)
	}
	got, err = detectZBPath(steam, pzPath)
	if err != nil || got != workshop(ZB_MOD_ID) {
		t.Fatalf("both: got %q, %v; want stable %q", got, err, workshop(ZB_MOD_ID))
	}
}

func TestDetectZBCopiesAndPrompt(t *testing.T) {
	tmpDir := t.TempDir()
	steam := filepath.Join(tmpDir, "Steam")
	pzPath := filepath.Join(steam, "steamapps", "common", "ProjectZomboid")
	if err := os.MkdirAll(pzPath, 0755); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{ZB_MOD_ID, ZB_BETA_MOD_ID} {
		if err := os.MkdirAll(filepath.Join(steam, "steamapps", "workshop", "content", PZ_APP_ID, id), 0755); err != nil {
			t.Fatal(err)
		}
	}
	copies := detectZBCopies(steam, pzPath)
	if len(copies) != 2 || copies[0].channel != "stable" || copies[1].channel != "beta" {
		t.Fatalf("copies = %+v, want stable then beta", copies)
	}

	for input, want := range map[string]string{"1\n": "stable", "2\n": "beta", "beta\n": "beta", "S\n": "stable", "x\n2\n": "beta"} {
		got, err := promptZBCopy(bufio.NewReader(strings.NewReader(input)), copies)
		if err != nil || got.channel != want {
			t.Fatalf("input %q: got %+v, %v; want %s", input, got, err, want)
		}
	}
	if _, err := promptZBCopy(bufio.NewReader(strings.NewReader("")), copies); err == nil {
		t.Fatal("expected an error on EOF")
	}
}
