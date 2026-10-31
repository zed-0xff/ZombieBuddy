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
| `-2` | ERROR - log errors only |
| `-1` | WARN  - log warnings + ERROR |
| `0`  | (default) INFO - log informational messages + WARN |
| `1`  | DEBUG - log more debugging information, including the patches transformation progress info |
| `2`  | TRACE - log a vast amount of details |

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

### frontend

Selects the UI for Java mod approval dialogs.

| Value | Description |
|-------|-------------|
| `auto` | (default) Automatically select the best available frontend |
| `swing` | Use Swing batch dialog + TinyFileDialogs for per-mod prompts |
| `tinyfd` | Use TinyFileDialogs for all prompts |
| `console` | Use stdin/stdout (headless mode) |

**Example:**

```
-javaagent:ZombieBuddy.jar=frontend=console --
```

---

### http_client_timeout

Timeout in seconds for HTTP connections and requests. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `5` | (default) |
| `30` | Use 30 seconds as both the connect and request timeout |

**Example:**

```
-javaagent:ZombieBuddy.jar=http_client_timeout=30 --
```

---

### http_cache_ttl

Time-to-live in seconds for the in-memory HTTP response cache. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `3600` | (default) Cache responses for 1 hour |
| `0` | Disable caching entirely |

**Example:**

```
-javaagent:ZombieBuddy.jar=http_cache_ttl=0 --
```

---

### config_dir

Directory used for ZombieBuddy configuration and cache files. Defaults to `~/.zombie_buddy`.

**Example:**

```
-javaagent:ZombieBuddy.jar=config_dir=/path/to/zombie_buddy_config --
```

---

### prop_prefix

Import JVM system properties into ZombieBuddy agent arguments. When set, any system property whose name starts with this prefix plus `.` is read, the prefix and dot are stripped, and the remaining name becomes a ZombieBuddy argument.

If `prop_prefix` is not specified, ZombieBuddy does not read system properties into agent arguments.

**Example:**

```
-Dzb.verbosity=2 -Dzb.policy=deny-new -javaagent:ZombieBuddy.jar=prop_prefix=zb --
```

This is equivalent to:

```
-javaagent:ZombieBuddy.jar=verbosity=2,policy=deny-new --
```

---

### experimental

Enables experimental patches. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:ZombieBuddy.jar=experimental --
```

---

### lua_server_port / lua_server_host / lua_server_allow_remote / lua_server_token

Controls the **experimental** HTTP Lua API. The server is disabled unless `lua_server_port` is set.

| Parameter | Description |
|-----------|-------------|
| `lua_server_port` | Port to listen on, or `random` to pick an unused port. Disabled when omitted. |
| `lua_server_host` | Bind address. Defaults to `127.0.0.1`. Non-loopback addresses require `lua_server_allow_remote=true`. |
| `lua_server_allow_remote` | `true` allows binding to a non-loopback address. Default: `false`. |
| `lua_server_token` | Optional bearer token. Must be at least 32 printable characters. When omitted or invalid, a random token is generated. |
| `lua_task_timeout` | Maximum time in milliseconds to wait for a Lua task. Default: `5000`. |

The token is written to `zbLuaAPI.token` next to `zbLuaAPI.txt` in the game cache directory (or the ZombieBuddy config directory as a fallback), with owner-only permissions where the filesystem supports them. A fixed-port server writes the token file when the game finishes initializing; a random-port server also writes `zbLuaAPI.txt` with the actual port.

`/lua` bodies are capped at 1 MiB and `/log` at 5,000 lines. `/lua` and `/log` require the token in either header:

```
X-ZombieBuddy-Token: <token>
Authorization: Bearer <token>
```

Example:

```
TOKEN=$(cat /path/to/zbLuaAPI.token)
curl -H "X-ZombieBuddy-Token: $TOKEN" \
     --data-binary @script.lua \
     http://127.0.0.1:4444/lua
```

> **Security warning:** `/lua` executes arbitrary Lua inside the game process. The `sandbox` query parameter is a compatibility aid, not a security boundary. Binding to a non-loopback address sends the bearer token over cleartext HTTP; use a firewall, VPN, or SSH tunnel. Do not expose this API to untrusted networks.

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
