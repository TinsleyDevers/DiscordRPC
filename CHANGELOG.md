# Changelog

## 1.2.0 — 2026-08-09

### The launcher-override fix

Discord only displays the activity of whichever application connected to it
first. The Modrinth App's built-in "Discord RPC" connects before any mod loads
(it even runs a small bridge inside the game process), so the mod's presence
was silently invisible for Modrinth App players — even though it was connected
and sending updates.

- The mod now detects the Modrinth App (bridge thread, environment, instance
  path) and tells you exactly how to fix it: a one-time chat notice after
  joining a world, a warning with steps in Settings, and a log entry.
  Short version: turn off Discord RPC in the Modrinth App settings, fully quit
  the app (check the tray), reopen it, then launch the game again.
- The warning can be dismissed permanently once you've handled it.

### New Minecraft + loader support

- **Fabric support** for Minecraft 26.1.x and 26.2 (needs Fabric API; Mod Menu
  integration included).
- **Minecraft 26.2** builds for both NeoForge and Fabric.
- Updated NeoForge builds for 1.21.1, 1.21.11 and 26.1.2; Forge 1.20.1 kept.
- Declared version ranges are now accurate per jar instead of optimistic.

### Settings screen overhaul

The editor now looks and behaves like a vanilla Minecraft screen:

- Real Minecraft tabs (the Create World style), the standard menu list
  background, and Done / Cancel in the footer.
- Vanilla text boxes, On/Off buttons and sliders replace the old custom-drawn
  widgets. Everything is keyboard-navigable and narration-friendly.
- The Discord card preview stays Discord-styled — it is a preview of Discord,
  after all — and is now cleaner, with hover hints and a clearer
  click-to-edit flow (including a Back button, which the old editor lacked).
- The image picker matches vanilla list styling, with tooltips for long names.
- Settings shows your connection state ("Connected to Discord as …") and a
  Reconnect button when the connection is down.
- Every label is translatable; the whole UI ships with proper language keys.

### Connection reliability

- Handshake is validated (the mod now reads Discord's READY reply and knows
  which account it's connected to).
- Reconnects use exponential backoff and answer Discord's PING frames, so the
  connection survives Discord restarts cleanly.
- Linux: the mod now finds the Discord socket for Flatpak and Snap installs.

### Internals

- One unified Gradle build (Gradle 9.7, ModDevGradle for NeoForge and legacy
  Forge, fabric-loom for Fabric) — `./gradlew assembleAll` builds every jar.
- Shared core (`core/`, `config/`) is identical across all seven targets;
  loader-specific code is reduced to a thin tick-and-keybind shim per loader.
- AFK detection no longer depends on loader input events (portable polling).

## 1.1.0

- Per-dimension and per-screen overrides with inherit-by-default fields.
- Server profiles, profile import/export, AFK detection, privacy toggles.

## 1.0.0

- Initial release: context profiles, placeholders, custom images, buttons.
