# Changelog

## 1.3.0 (2026-09-25)

Your own images on Discord, no bot or developer account needed.

- Open the Large or Small Image editor and press Upload Image..., or drag a PNG, JPG or GIF onto the game window. The mod uploads it to catbox.moe (a free image host) and your presence shows it on the next update. The first upload asks once before anything leaves your computer.
- Everything you upload or link lands in a new Recent row at the top of the picker, newest first, with thumbnails, so switching back is one click. Right-click one to drop it from the list.
- You can also paste a link to any image online into the image field. The card preview in the editor loads it so you can see what Discord will show.
- Uploads are cleaned up first: big pictures are shrunk to 1024 pixels, tiny pixel art is scaled up with sharp edges so Discord doesn't blur it, and photo metadata such as location is stripped. GIFs are sent as they are so they stay animated.
- The same file is never uploaded twice, and a copy of every image you upload is kept in config/discordrpc/images.
- Fixed: images dropped into config/discordrpc/images used to show in the editor but never on Discord, because Discord had no way to reach them. They now sit under Your Images in the picker, and clicking one uploads it. The editor also warns when the current image is one Discord can't show.
- The image field takes up to 256 characters so full links fit.

## 1.2.3 (2026-09-09)

- Each jar now says which Minecraft version it actually runs on. The 1.21.1 build claimed all of NeoForge 21.x and the 26.2 build claimed everything from 26.2 up, so the download pages offered versions the jar would refuse. Nothing about the mod changed, only what it admits to supporting.
- The default singleplayer profile shows "Overworld, Plains" instead of using a dash between them.
- Asks for ZambieLib 1.1 or newer, matching what it is built against.
- Inside the repo: the config model, the profile model, the Discord socket and the presence manager were seven identical copies, one per target. They live in common/ now, so a fix lands once instead of seven times.

## 1.2.2 (2026-09-08)

Small maintenance release.

- Every build now really runs on ZambieLib. 1.2.1 declared the dependency on all seven targets but only the 1.21.1 jar used it; the other six carried their own copy of the crash-safe config writer. All targets share the ZambieLib one now, so the config code is identical across versions again.
- Builds pick up ZambieLib by target (1.1.0+<target>), which is what makes the above possible.
- Nothing changes for players: ZambieLib 1.0.0 or newer next to the mod, same as before.

## 1.2.1 (2026-08-16)

Stability and correctness release: no new features, a lot of fixed ones.

Also in this build: a proper mod icon, a full metadata pass so the mod list entry finally looks right, and the 1.21.1 build now runs on ZambieLib, which it requires from here on.

### Your config can no longer be lost

- Saves are atomic (written to a temp file, then swapped in), so a crash or
  power loss mid-save can never truncate `config.json`.
- A config that fails to parse is preserved as `config.json.corrupt` instead of
  being silently overwritten with defaults; hand-recoverable, never destroyed.
- One bad value no longer discards the whole file: every setting and every
  profile is loaded independently, and malformed entries are skipped.

### Discord updates: deduplicated and rate limited

- Identical presence payloads are no longer re-sent every few seconds.
- Updates are rate limited to one per 4 seconds (Discord allows ~5 per 20s),
  with rapid changes coalesced into a single trailing update, so fast menu
  clicking or dimension hopping can no longer make the presence go stale.
- A transient game-state race (e.g. leaving a world mid-update) no longer tears
  down a healthy Discord connection; only real I/O failures trigger reconnect.
- The connection lifecycle now lives entirely on the worker thread (no more
  cross-thread races on reconnect state), replies are matched by nonce, and
  game exit can no longer hang on a wedged pipe read.

### Fixed features

- **Bundled images actually install again**: the manifest referenced CamelCase
  filenames while the jar contains lowercase ones, so 0 of 14 images were being
  copied to `config/discordrpc/images/` from a release jar. Image keys also
  resolve case-insensitively now (fixes defaults on Linux/macOS).
- **Buttons are editable everywhere**: empty button slots now render as
  clickable ghost rows in the preview card; previously a fresh install had no
  way to add a presence button outside server overrides.
- **The small-image badge is clickable**: it used to hit-test as the large
  image and open the wrong editor.
- **Party size on servers**: no more permanently-full "(5 of 5)"; the real
  capacity comes from the server-list ping when known, and the party is omitted
  when it isn't. `{max_players}` shows "?" instead of echoing the online count.
- **"Show in main menu" works**: the toggle existed but was never read.
- **Importing a Main Menu / Singleplayer / Multiplayer profile** now applies it
  onto that tab's profile instead of creating an invisible duplicate that could
  hijack the presence via priority.
- Unsaved Multiplayer-tab edits survive a detour into a server-profile editor.
- Export failures are reported instead of always claiming success.

### Safety and polish

- Destructive actions (deleting a profile file, removing a server profile,
  Reset Everything) now require a confirming second click.
- Config screen no longer re-decodes every image on each open and window
  resize; directory listings are no longer leaked (one file handle per rendered
  frame on the import screen).
- The Modrinth App conflict check re-runs periodically instead of caching a
  one-shot result, and no longer false-positives on developers with
  `MODRINTH_*` environment variables.
- The "is Discord running?" warning logs once instead of every reconnect
  attempt; server profile names auto-uniquify (duplicate names collided as
  export filenames); emoji are no longer split in half by length truncation.
- Client-only marking (`displayTest`, `side="CLIENT"`) in all Forge/NeoForge
  metadata; no more red version mismatch in the server list; mod-list logo
  added; 1.21.1 `pack_format` corrected to 34; LICENSE file shipped.
- The mod version now lives in exactly one place (root Gradle build) instead
  of thirteen.

## 1.2.0 (2026-08-09)

### The launcher-override fix

Discord only displays the activity of whichever application connected to it
first. The Modrinth App's built-in "Discord RPC" connects before any mod loads
(it even runs a small bridge inside the game process), so the mod's presence
was silently invisible for Modrinth App players, even though it was connected
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
- The Discord card preview stays Discord-styled (it is a preview of Discord,
  after all) and is now cleaner, with hover hints and a clearer
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
  Forge, fabric-loom for Fabric); `./gradlew assembleAll` builds every jar.
- Shared core (`core/`, `config/`) is identical across all seven targets;
  loader-specific code is reduced to a thin tick-and-keybind shim per loader.
- AFK detection no longer depends on loader input events (portable polling).

## 1.1.0

- Per-dimension and per-screen overrides with inherit-by-default fields.
- Server profiles, profile import/export, AFK detection, privacy toggles.

## 1.0.0

- Initial release: context profiles, placeholders, custom images, buttons.
