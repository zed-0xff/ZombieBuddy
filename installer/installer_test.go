package main

import (
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

			// Backslashes must be escaped in VDF ("\\"), otherwise the parser
			// treats them as escape sequences and corrupts Windows paths.
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
