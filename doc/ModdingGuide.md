# ZombieBuddy Modding Guide

This guide covers creating Java mods that use ZombieBuddy for bytecode patching and Lua integration.

---

## Mod Structure

Create a standard Project Zomboid mod structure:

```
YourMod/
├── [version]/
│   ├── mod.info
│   └── media/
│       └── java/
│           └── YourMod.jar
└── common/
```

---

## Configure mod.info

Add the following entries to your `mod.info` file:

```ini
require=\ZombieBuddy
javaJarFile=media/java/YourMod.jar
javaPkgName=com.yourname.yourmod
ZBVersionMin=1.0.0
ZBVersionMax=1.5.0
```

| Field | Description |
|-------|-------------|
| `require=\ZombieBuddy` | Declares dependency on ZombieBuddy framework |
| `javaJarFile` | Path to your JAR file relative to the mod version directory. **Required** for Java code. Only a single JAR per mod is supported. |
| `javaPkgName` | The package name for your Main class and patches. **Mandatory** if `javaJarFile` is specified. |
| `ZBVersionMin` | (Optional) Minimum ZombieBuddy version required (inclusive) |
| `ZBVersionMax` | (Optional) Maximum ZombieBuddy version required (inclusive) |
| `javaPreload` | (Optional) Set to `true` to request early loading. The JAR must also be signed and declare preload support in its manifest. |

### Client/server JAR paths

ZombieBuddy skips loading a Java mod when the environment and JAR path don't match:
- **On a dedicated server**: mods whose `javaJarFile` path contains `media/java/client/` are skipped (client-only).
- **On the game client**: mods whose `javaJarFile` path contains `media/java/server/` are skipped (server-only).

Use a path that does not contain `client/` or `server/` (e.g. `media/java/YourMod.jar`) for code that runs on both.

### Important notes

- `javaPkgName` is **mandatory** when `javaJarFile` is specified
- The Main class is always named `Main` (if present)
- The Main class is **optional** - patches will be applied even if Main class doesn't exist
- The JAR file must contain the package specified in `javaPkgName`
- Only one `javaJarFile` and one `javaPkgName` entry per mod
- [test isolation requires PZ restart, not just save reload](#13)

---

## Java Project Setup

Set up a Gradle project with the following dependencies:

```gradle
dependencies {
    // ZombieBuddy API (compile-only, provided at runtime)
    compileOnly files("path/to/ZombieBuddy.jar")
    
    // Project Zomboid classes (compile-only)
    compileOnly files("path/to/ProjectZomboid/Contents/Java")
}
```

---

## Main Class (Optional)

Create a Main class in the package specified by `javaPkgName`. The `main(String[])` method is optional - if it exists, it will be automatically executed when the mod loads:

```java
package com.yourname.yourmod;

public class Main {
    public static void main(String[] args) {
        System.out.println("[YourMod] Initializing...");
        // Your initialization code here
    }
}
```

**Patches-only mod**: If your mod only contains patches and doesn't need initialization code, you can omit the Main class entirely. ZombieBuddy will automatically discover and apply all `@Patch` annotated classes in the package.

---

## Preload Mods

Signed mods can request early loading with `javaPreload=true` in `mod.info` and the matching preload manifest entry in the JAR. When the user approves preload, ZombieBuddy loads the JAR during Java agent startup on the next game launch.

Add the manifest attribute to the JAR you distribute:

```text
ZB-Preload: true
```

With Gradle's standard `jar` task:

```gradle
jar {
    manifest {
        attributes(
            'ZB-Preload': 'true'
        )
    }
}
```

If you distribute a shaded JAR, add the same attribute to `shadowJar` instead, or in addition:

```gradle
shadowJar {
    manifest {
        attributes(
            'ZB-Preload': 'true'
        )
    }
}
```

ZombieBuddy requires both `javaPreload=true` in `mod.info` and `ZB-Preload: true` in the JAR manifest. This prevents a loose `mod.info` edit from silently turning an ordinary signed JAR into an early-loaded one.

For preloaded mods, lifecycle entry points are split by phase:

- `PreMain.premain(String[] args)` is executed at ZombieBuddy `premain` time, before regular Project Zomboid mod loading.
- `Main.main(String[] args)` is still executed later at the regular Java mod load time.

Use `PreMain` only for work that truly needs early hooks. Put normal initialization in `Main` so it runs with the rest of the mod-loading flow.

---

## Creating Patches

### Basic Patch Structure

```java
package com.yourname.yourmod.patches;

import me.zed_0xff.zombie_buddy.annotations.Patch;

@Patch(className = "zombie.SomeGameClass", methodName = "someMethod", warmUp = true)
public class MyPatch {
    @Patch.OnEnter
    public static void enter() {
        System.out.println("[YourMod] Intercepted method call!");
    }
    
    @Patch.OnExit
    public static void exit() {
        System.out.println("[YourMod] Method finished!");
    }
}
```

### Skipping the Original Method

Use `skipOn = true` in `@Patch.OnEnter`. The advice method must return a `boolean`. If it returns `true`, the original method is skipped.

```java
@Patch(className = "zombie.SomeClass", methodName = "someMethod")
public class SkipExample {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        if (shouldSkip) {
            return true; // Original method will NOT be executed
        }
        return false; // Original method will proceed normally
    }
}
```

**Important**: 
- The advice method must return a primitive `boolean`.
- If skipped, the original method returns its default value (e.g., `0` for `int`, `null` for objects).

### Patch Options

| Option | Description |
|--------|-------------|
| `className` | Fully qualified name of the target class |
| `methodName` | Name of the method to patch |
| `warmUp` | If `true`, forces the class to load before patching (needed for some game classes) |
| `isAdvice` | If `true` (default), uses advice-based patching. If `false`, uses method delegation (complete replacement) |
| `strictMatch` | If `true`, an advice method with no parameters matches only target methods with no parameters. Default `false` matches any overload. |

### Parameter Bindings

Advice methods can bind to parts of the intercepted call using parameter annotations:

| Annotation | Available in | Description |
|------------|-------------|-------------|
| `@Patch.This` | OnEnter, OnExit | The target object (`this`). Not available for static target methods. |
| `@Patch.Argument(n)` | OnEnter, OnExit | The n-th method argument (0-based). `readOnly = false` to overwrite. |
| `@Patch.AllArguments` | OnEnter, OnExit | All arguments as `Object[]`. `readOnly = false` to overwrite. |
| `@Patch.Return` | OnExit only | The method's return value. `readOnly = false` to overwrite. |
| `@Patch.Thrown` | OnExit only | The exception thrown, or `null` if none. |
| `@Patch.Local("name")` | OnEnter, OnExit | A named local variable from the target's debug info. |
| `@Patch.Field` | OnEnter, OnExit | An instance or static field of the target class (writable by default). `readOnly = true` for read-only binding. |

```java
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "attack")
public class AttackPatch {
    @Patch.OnEnter
    public static void enter(@Patch.This Object self,
                             @Patch.Argument(0) Object target,
                             @Patch.AllArguments Object[] args) { }

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) int result,
                            @Patch.Thrown Throwable thrown) {
        if (thrown == null) result = result * 2;
    }
}
```

### Reading and Writing Fields (@Patch.Field)

`@Patch.Field` binds a parameter to an instance or static field of the target class. By default the value is written back after the advice returns. Use `readOnly = true` for read-only binding.

The field name is inferred from the parameter name when the annotation `value()` is omitted. Provide an explicit name when the parameter name must differ. Provide multiple names and the engine tries them in order, using the first one that exists on the target class — useful for patches that must work across game versions that renamed a field. Multi-name resolution requires the target class to be already loaded, so it cannot be used in preload-time patches.

```java
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class PlayerUpdatePatch {
    @Patch.OnEnter
    public static void enter(@Patch.This Object self,
                             @Patch.Field(readOnly = true) String username,      // read-only
                             @Patch.Field("maxSpeed") float speed,               // read/write (default)
                             @Patch.Field({"speedNew", "speed"}) float spd,      // tries "speedNew", falls back to "speed"
                             @Patch.Field int stamina) {                         // read/write (default)
        if (stamina < 10) stamina = 10;  // write-back happens after advice returns
    }
}
```

### Accessing Private Members with Handles (@Patch.VarHandle)

**VarHandle** (direct field access without reflection):

```java
@Patch(className = "zombie.SomeClass", methodName = "someMethod")
public class SomePatch {
    @Patch.VarHandle(type = int.class)
    public static VarHandle privateCount;

    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        int v = (int) privateCount.get(self);
    }
}
```

**Attributes**:

| Attribute | Description |
|-----------|-------------|
| `value` / `name` | Member name(s) to look up. Empty = infer from field/parameter name. Multiple = tried in order. |
| `className` | Fully qualified owner class. Empty = infer from `@Patch.className()`. Mutually exclusive with `owner`. |
| `owner` | Type-safe class reference. Mutually exclusive with `className`. |
| `optional` | `false` (default): drop the patch class if the member is missing. `true`: leave the handle `null`. |
| `returnType` | **Required for MethodHandle.** Return type of the target method. |
| `parameterTypes` | **Required for MethodHandle.** Parameter types of the target method. |
| `type` | **Required for VarHandle.** Type of the target field. |

Keys are parameter names; values are the actual field names on the target class. The map is immutable — declare the parameter `final` (the annotation processor will warn otherwise).

---

## Exposing Classes to Lua

### Annotation-based (recommended)

**Full class exposure**: Annotate a class with `@Exposer.LuaClass`. The loader discovers it in your package and registers it with the game's Lua engine.

```java
import me.zed_0xff.zombie_buddy.Exposer;

@Exposer.LuaClass
public class MyLuaApi {
    public String greet(String who) {
        return "Hello, " + who;
    }
}
```

**Global Lua functions only**: Add `@LuaMethod(name = "luaName", global = true)` to **static** methods. The class does **not** need `@Exposer.LuaClass`.

```java
import se.krka.kahlua.integration.annotations.LuaMethod;

public class MyGlobals {
    @LuaMethod(name = "myGlobalFunc", global = true)
    public static String myGlobalFunc(String arg) {
        return "got: " + arg;
    }
}
```

In Lua you can then call `myGlobalFunc("foo")`.

### Manual API

For runtime class exposure:

```java
import me.zed_0xff.zombie_buddy.Exposer;

// In your initialization code
Exposer.exposeClass(MyCustomClass.class);
```

---

## Building Your Mod

Build your JAR file and place it in the location specified in `mod.info`:

```bash
gradle build
cp build/libs/YourMod.jar ~/Zomboid/mods/YourMod/[version]/media/java/
```

---

## Signing Your Mod (Optional)

ZombieBuddy supports **ZBS signatures** - Ed25519 cryptographic signatures that verify mod authorship. When a signed mod is loaded, ZombieBuddy can verify that the JAR was signed by the author whose public key is published on their Steam profile.

For the trust model and limitations of signing, see [Mod Signing](ModSigning.md).

**Why sign your mod?**
- Users can verify the mod hasn't been tampered with
- Auto-approval for trusted authors (users can mark your Steam ID as trusted)
- Builds trust in the modding community

### Setup (one-time)

1. Generate an Ed25519 private key (use homebrew openssl on macOS):

```bash
openssl genpkey -algorithm ed25519 -outform DER -out ~/.signing/ed25519-private.der
```

2. Add your credentials to `~/.gradle/gradle.properties`:

```properties
zbsSteamID64=76561198012345678
zbsPrivateKeyFile=/Users/you/.signing/ed25519-private.der
```

### Using the Gradle plugin

Add to your `settings.gradle`:

```groovy
includeBuild '/path/to/zb-gradle-plugin'
// Or after the plugin is published to Gradle Plugin Portal:
// (no includeBuild needed)
```

Add to your `build.gradle`:

```groovy
plugins {
    id 'java'
    id 'io.github.zed-0xff.zb-gradle-plugin' version '1.0.0'
}

zbSigning {
    jarTask = 'jar'  // or 'shadowJar' for fat JARs
}
```

### Build and sign

```bash
gradle build
```

Output:

```
> Task :signJarZBS
Add to Steam profile: JavaModZBS:cafebabe0123456789abcdef0123456789abcdef0123456789abcdef01234567

BUILD SUCCESSFUL in 1s
```

Add the `JavaModZBS:...` string to your Steam profile summary (one-time setup). This publishes your public key so users can verify your signatures.

**Files produced:**
- `YourMod.jar` - The mod JAR
- `YourMod.jar.zbs` - The signature file (distribute alongside the JAR)

---

## Testing

1. Ensure ZombieBuddy is installed and configured (see [Installation Guide](Installation.md))
2. Enable your mod in the Project Zomboid mod manager
3. Launch the game and check the console for your mod's output
4. Use `verbosity=2` in launch options for detailed patch logging

---

## Tips

- **Package naming**: Use a unique package name to avoid conflicts (e.g., `com.yourname.yourmod`)
- **Warm-up classes**: Some game classes need to be "warmed up" before patching. Set `warmUp = true` in your `@Patch` annotation
- **Advice vs Delegation**: Use `isAdvice = true` (default) for intercepting methods. Use `isAdvice = false` for complete method replacement (only one delegation per method)
- **Retransformation**: Patches can be applied to already-loaded classes, but MethodDelegation patches work best on classes that haven't loaded yet

---

## Example Mods

Looking for examples to learn from? Check out these mods built with ZombieBuddy:

- **[ZBLuaPerfMon](https://github.com/zed-0xff/ZBLuaPerfMon)**: A real-time Lua performance monitor and On-Screen Display (OSD). Demonstrates high-precision timing and core game engine patching.
- **[ZBHelloWorld](https://github.com/zed-0xff/ZBHelloWorld)**: A simple example mod demonstrating how to patch UI rendering methods. Shows the basic structure with `javaPkgName` and a Main class.
- **[ZBetterWorkshopUpload](https://github.com/zed-0xff/ZBetterWorkshopUpload)**: A practical mod demonstrating workshop integration, Lua exposure, and complex patching.
- **[ZBMacOSHideMenuBar](https://github.com/zed-0xff/ZBMacOSHideMenuBar)**: Fixes the macOS menu bar issue in borderless windowed mode. Demonstrates display patching and macOS-specific functionality.
- **[ZBBetterFPS](https://github.com/zed-0xff/ZBBetterFPS)**: Optimizes FPS by reducing render distance. Demonstrates runtime configuration and render engine patching.
- **[ZItemTiers](https://github.com/zed-0xff/ZItemTiers)**: Probability-based item rarity (Common → Legendary) with stat bonuses; optional ZombieBuddy for weapon weight and other Java patches.

---

## Sharing Your Source Code

We strongly encourage modders to share their source code! This helps others learn, contribute, and maintain mods when you're unavailable.

**Bundle source with your mod**: Include a `src/` directory in your mod distribution:

```
YourMod/
├── [version]/
│   ├── mod.info
│   ├── media/
│   │   └── java/
│   │       └── YourMod.jar
│   └── src/              # Optional: include source code
│       └── com/
│           └── yourname/
│               └── yourmod/
└── common/
```

**Share on GitHub**: Create a public repository for version control, issue tracking, and community contributions.

Open source mods benefit the entire Project Zomboid modding community!
