# ZombieBuddy

A powerful framework for Project Zomboid modders that enables Java bytecode manipulation and runtime patching of game classes using ByteBuddy.

## What is ZombieBuddy?

<img src="icon_256.png" align="right" alt="ZombieBuddy Icon" width="128" height="128">

ZombieBuddy is a Java agent-based framework that allows modders to:
- **Patch game classes at runtime** using bytecode manipulation
- **Expose Java classes to Lua** for enhanced modding capabilities
- **Apply patches declaratively** using simple annotations
- **Load Java code from mods** seamlessly

Built on top of [ByteBuddy](https://bytebuddy.net/), ZombieBuddy provides a clean, annotation-based API for intercepting and modifying game behavior without requiring access to the game's source code.

### Why ZombieBuddy?

Previously, Java mods for Project Zomboid required bundling `.class` files and manually replacing game files. ZombieBuddy makes this better:

1. **No manual file replacement**: Automatically loads and applies patches at runtime
2. **Precise patching**: Patch specific methods with surgical precision - multiple mods can patch the same class without conflicts, and game updates are less likely to break your mod

## Features

- 🎯 **Annotation-based patching**: Use `@Patch` annotations to declare method patches
- 🔄 **Runtime class transformation**: Patch classes that are already loaded using retransformation
- 📦 **Automatic patch discovery**: Scans for patch classes automatically
- 🔗 **Lua integration**: Expose Java classes and global functions to Lua
- ⚡ **Advice and Method Delegation**: Support for both advice-based and delegation-based patching
- 🔍 **Verbose logging**: Configurable verbosity levels for debugging

## Quick Start

### For End Users

1. **Windows**: Download and run [ZombieBuddyInstaller.exe](https://github.com/zed-0xff/ZombieBuddy/releases/), then choose the launch modes to patch. The installer shows a confirmation preview before applying changes.
2. **macOS/Linux**: Copy `ZombieBuddy.jar` to the game directory and add `-javaagent:ZombieBuddy.jar --` to launch options

📖 **[Full Installation Guide](doc/Installation.md)** - Security warnings, manual installation, policy modes

📖 **[Uninstall Guide](doc/Uninstall.md)** - Remove launch options, installed files, and optional config data

### For Modders

1. Add `require=\ZombieBuddy`, `javaJarFile`, and `javaPkgName` to your `mod.info`
2. Create patches using `@Patch` annotations
3. Build your JAR and place it in `media/java/`

📖 **[Modding Guide](doc/ModdingGuide.md)** - Complete guide to creating Java mods

## Documentation

| Document | Description |
|----------|-------------|
| [Installation Guide](doc/Installation.md) | End-user installation, security, and policy configuration |
| [Uninstall Guide](doc/Uninstall.md) | Removing ZombieBuddy and optional config data |
| [Command-Line Parameters](doc/CommandLine.md) | All agent parameters: verbosity, policy, experimental, etc. |
| [Modding Guide](doc/ModdingGuide.md) | Creating patches, Lua exposure, mod signing, examples |
| [Mod Signing](doc/ModSigning.md) | What signatures prove, what they do not prove, and how author trust works |
| [Lua API Reference](doc/LuaAPI.md) | Events, Watches, and Java mod status APIs |
| [Dev/Debug Functions](doc/DevDebugFunctions.md) | Lua utilities: `zbinspect`, `zbmethods`, `zbgrep`, `zbmap`, etc. |

## Example: Creating a Patch

```java
import me.zed_0xff.zombie_buddy.Patch;

@Patch(className = "zombie.SomeGameClass", methodName = "someMethod")
public static class MyPatch {
    @Patch.OnEnter
    public static void enter() {
        System.out.println("Method called!");
    }
}
```

## Example Mods

- **[ZBLuaPerfMon](https://github.com/zed-0xff/ZBLuaPerfMon)** - Real-time Lua performance monitoring
- **[ZBHelloWorld](https://github.com/zed-0xff/ZBHelloWorld)** - Simple example demonstrating basic patching
- **[ZBetterWorkshopUpload](https://github.com/zed-0xff/ZBetterWorkshopUpload)** - Workshop integration and Lua exposure
- **[ZBMacOSHideMenuBar](https://github.com/zed-0xff/ZBMacOSHideMenuBar)** - macOS display patching
- **[ZBBetterFPS](https://github.com/zed-0xff/ZBBetterFPS)** - Render engine optimization
- **[ZItemTiers](https://github.com/zed-0xff/ZItemTiers)** - Item rarity with optional Java patches

## Requirements

- **Project Zomboid** (Build 42+)
- **Java 17** (required by the game)
- **Gradle** (for building Java mods)

## ☕ Support the Project

If you find ZombieBuddy useful, consider supporting its development:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/zed_0xff)

## License

Copyright (c) 2025-2026 Andrey "Zed" Zaikin

This project is licensed under a permissive open-source license. See [LICENSE.txt](LICENSE.txt) for details.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Links

- **GitHub**: https://github.com/zed-0xff/ZombieBuddy
- **Steam Workshop**: https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853
- **Related**: [ZBSpec](https://github.com/zed-0xff/ZBSpec) - Testing framework for PZ mods

## Disclaimer

This mod uses bytecode manipulation to modify game behavior. **Java mods enabled through ZombieBuddy have unrestricted access to your system and can execute arbitrary code.** Use at your own risk. Only install Java mods from trusted sources and review their source code when available.
