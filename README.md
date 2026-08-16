# DiscordPresence

Show your Minecraft session on Discord, exactly the way you want it.

Open the editor (**F6**, or the Config button in the mod list) and click any part of
the live Discord card to change it: text, images, buttons, timestamp. The mod
switches profiles automatically as you move between the main menu, singleplayer
and multiplayer.

[Download on Modrinth](https://modrinth.com/mod/discordpresence)

## Supported versions

| Minecraft | Loader   | Java | Jar |
|-----------|----------|------|-----|
| 26.2      | NeoForge | 25   | `DiscordPresence-1.2.1+26.2-neoforge.jar` |
| 26.2      | Fabric   | 25   | `DiscordPresence-1.2.1+26.2-fabric.jar` |
| 26.1.x    | NeoForge | 25   | `DiscordPresence-1.2.1+26.1.2-neoforge.jar` |
| 26.1.x    | Fabric   | 25   | `DiscordPresence-1.2.1+26.1.2-fabric.jar` |
| 1.21.11   | NeoForge | 21   | `DiscordPresence-1.2.1+1.21.11-neoforge.jar` |
| 1.21.1    | NeoForge | 21   | `DiscordPresence-1.2.1+1.21.1-neoforge.jar` |
| 1.20.1 - 1.20.4 | Forge | 17  | `DiscordPresence-1.2.1+1.20.1-forge.jar` |

The Fabric builds need [Fabric API](https://modrinth.com/mod/fabric-api);
[Mod Menu](https://modrinth.com/mod/modmenu) is optional but adds a Configure button.

## Features

- **Click-to-edit preview**: the settings screen shows the actual Discord card;
  click the details line, an image, a button or the timestamp to edit that part,
  and watch the card update as you type.
- **Context profiles**: separate presence for the Main Menu, Singleplayer and
  Multiplayer, switched automatically.
- **Per-server profiles**: a custom look for any server, matched by address
  (for example `hypixel.net`).
- **Overrides**: change only what matters per dimension (Overworld / Nether /
  End / any modded dimension) or per menu screen (title, server list, world
  list, …). Fields you don't touch inherit from the parent profile.
- **26 live placeholders**: `{player}`, `{server}`, `{dimension}`, `{biome}`,
  `{x}` `{y}` `{z}`, `{health}`, `{hunger}`, `{armor}`, `{xp_level}`,
  `{gamemode}`, `{difficulty}`, `{online}`, `{max_players}`, `{ping}`, `{fps}`,
  `{time}`, `{day}`, `{weather}`, `{held_item}`, `{version}`, `{world}`,
  `{server_ip}`, `{max_health}`, `{modcount}` and more, usable in any text field.
- **Custom images**: drop PNGs into `config/discordrpc/images/` and pick them
  in the image picker.
- **Privacy**: one-click toggles to hide your server IP and coordinates, plus
  AFK detection with a configurable timeout.
- **Profile sharing**: export and import profiles as JSON files.
- **Resilient connection**: reconnects automatically (with backoff) when
  Discord starts late or restarts, and answers Discord's pings. Works with
  Flatpak and Snap installs of Discord on Linux.

## The presence isn't showing?

**Discord shows the activity of whichever app connected to it first.**

- **Modrinth App**: the launcher's built-in "Discord RPC" connects before any
  mod can, so your custom presence is accepted but never displayed. The mod
  detects this and warns you in chat and in its Settings tab. Fix: Modrinth App
  settings → turn **Discord RPC off** → **fully quit** the Modrinth App (check
  the system tray) → reopen it → launch the game again.
- Other launchers with built-in Discord status (GDLauncher, XMCL, …) need the
  same treatment: turn their Discord integration off and restart the launcher.
- Discord's own setting **Activity Privacy → Share your detected activities
  with others** must be on.
- The Discord *desktop* app must be running; the browser version has no local
  IPC and can't show rich presence from mods.

## Building

Requires Java 25 on the Gradle JVM (per-target toolchains handle the rest).

```bash
./gradlew assembleAll
```

Single targets:

```bash
./gradlew :26.2-fabric:build
./gradlew :26.2-neoforge:build
./gradlew :26.1.2-fabric:build
./gradlew :26.1.2-neoforge:build
./gradlew :1.21.11-neoforge:build
./gradlew :1.21.1-neoforge:build
./gradlew :1.20.1-forge:build
```

Jars land in `<target>/build/libs/`. Development clients: `./gradlew :<target>:runClient`.

Each target is a standalone source tree because Minecraft's client APIs drift
between versions. The shared files (`core/DiscordIPC`, `core/RPCManager`,
`core/LauncherConflict`, `config/ModConfig`, `config/RichPresenceProfile`, and
the resources) are byte-identical across all seven targets and can be copied
verbatim. **`core/PlaceholderEngine` is NOT copy-safe**: it legitimately
differs per version (Identifier vs ResourceLocation, clock APIs, `{version}`
fallback, per-loader mod count), as do `client/` and `gui/`. When editing
shared logic, change it in `26.2-neoforge` first and mirror outward; when
editing the per-version files, port the change by hand.

The mod version is set once, in the root `build.gradle`; subprojects and the
loader metadata files pick it up automatically.

## License

[MIT](LICENSE)
