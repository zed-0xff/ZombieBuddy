# ZombieBuddy Command-Line Parameters

ZombieBuddy accepts configuration parameters through the Java agent argument string. Parameters are passed as `key=value` pairs separated by commas.

---

## Usage

**macOS / Linux:**

```
-javaagent:ZombieBuddy.jar=param1=value1,param2=value2 --
```

**Windows:**

```
-agentlib:zbNative=param1=value1,param2=value2 --
```

> **Note:** The `--` at the end is mandatory.

---

## Parameters

### verbosity

Controls the amount of logging output.

| Value | Description |
|-------|-------------|
| `0` | (default) Errors only |
| `1` | Shows patch transformations |
| `2` | Shows all debug output |

**Example:**

```
-javaagent:ZombieBuddy.jar=verbosity=2 --
```

Can also be set via the `ZB_VERBOSITY` environment variable (overrides the command-line value).

---

### policy

Controls how ZombieBuddy handles unknown or changed Java mod JARs.

| Value | Description |
|-------|-------------|
| `prompt` | (default) Ask via a native dialog for each unknown/changed JAR |
| `deny-new` | Silently skip any JAR that isn't already approved. No dialogs shown |
| `allow-all` | Load every JAR without prompting. **Not recommended** - use only in controlled setups |

**Example:**

```
-javaagent:ZombieBuddy.jar=policy=deny-new --
```

The policy is locked during agent startup, before any Java mod is loaded. A later-loading Java mod cannot change it.

---

### allow_unsigned_mods

Controls whether mods without ZBS signatures are allowed.

| Value | Description |
|-------|-------------|
| `true` | (default) Allow mods without `.zbs` signature files |
| `false` | Treat missing `.zbs` sidecar as an invalid signature (blocked unless `policy=allow-all`) |

**Example:**

```
-javaagent:ZombieBuddy.jar=allow_unsigned_mods=false --
```

> **Note:** Invalid signatures (present `.zbs` but verification fails) are always blocked when ZBS verification is enabled.

---

### approval_frontend

Selects the UI for Java mod approval dialogs.

| Value | Description |
|-------|-------------|
| `auto` | (default) Automatically select the best available frontend |
| `swing` | Use Swing batch dialog + TinyFileDialogs for per-mod prompts |
| `tinyfd` | Use TinyFileDialogs for all prompts |
| `console` | Use stdin/stdout (headless mode) |

**Example:**

```
-javaagent:ZombieBuddy.jar=approval_frontend=console --
```

---

### batch_approval_timeout

Maximum time (in seconds) to wait for the batch Swing approval subprocess.

| Value | Description |
|-------|-------------|
| `0` | (default) No timeout - wait until the user responds |
| `N` | Wait up to N seconds before timing out |

**Example:**

```
-javaagent:ZombieBuddy.jar=batch_approval_timeout=60 --
```

---

### experimental

Enables experimental patches. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:ZombieBuddy.jar=experimental --
```

---

### patches_jar

Load additional patch JARs at startup. Useful for development or testing patches without packaging them as a mod.

**Format:** `path:package_name` - multiple entries separated by semicolons.

**Example:**

```
-javaagent:ZombieBuddy.jar=patches_jar=/path/to/MyPatches.jar:com.example.patches --
```

Multiple JARs:

```
-javaagent:ZombieBuddy.jar=patches_jar=/path/to/First.jar:com.first;/path/to/Second.jar:com.second --
```

---

### expose_classes

Expose Java classes to Lua at startup. Comma-separated list of fully-qualified class names.

**Example:**

```
-javaagent:ZombieBuddy.jar=expose_classes=com.example.MyClass,com.example.OtherClass --
```

---

### exit_after_game_init

Exit the game immediately after initialization completes. Useful for testing or CI pipelines. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:ZombieBuddy.jar=exit_after_game_init --
```

---

## Combining Parameters

Multiple parameters can be combined with commas:

```
-javaagent:ZombieBuddy.jar=verbosity=1,policy=deny-new,allow_unsigned_mods=false --
```

Windows example:

```
-agentlib:zbNative=verbosity=2,experimental --
```

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `ZB_VERBOSITY` | Sets verbosity level (overrides command-line `verbosity` parameter) |
