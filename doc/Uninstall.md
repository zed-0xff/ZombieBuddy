# Uninstalling ZombieBuddy

## Windows

1. Open Project Zomboid properties in Steam and remove the ZombieBuddy launch option:

   ```
   -agentlib:zbNative ... --
   ```

2. Delete these files from the Project Zomboid game directory:
   - `ZombieBuddy.jar`
   - `ZombieBuddy.jar.new`, if present
   - `zbNative.dll`

3. Optional: delete ZombieBuddy's config directory if you also want to remove approval decisions and caches:
   - Default location: `%USERPROFILE%\.zombie_buddy`
   - Usually: `C:\Users\<your username>\.zombie_buddy`

## macOS / Linux

1. Open Project Zomboid properties in Steam and remove the ZombieBuddy launch option:

   ```
   -javaagent:ZombieBuddy.jar ... --
   ```

2. Delete these files from the Project Zomboid game directory:
   - `ZombieBuddy.jar`
   - `ZombieBuddy.jar.new`, if present

3. Optional: delete ZombieBuddy's config directory if you also want to remove approval decisions and caches:
   - Default location: `~/.zombie_buddy`
