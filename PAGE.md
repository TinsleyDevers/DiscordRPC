# DiscordPresence page copy

Paste-ready copy for the Modrinth and CurseForge pages. Modrinth's on-site
search only reads the project name, the summary and the categories, so those
three carry the keywords people actually type (discord rich presence, discord
rpc, custom discord status). The page body is for Google and for convincing
humans once they land.

## Name field

Modrinth: Discord Presence
CurseForge: DiscordPresence

(Displayed name only. The space makes searches for "presence" and "rich
presence" match the name field, which search weighs highest. CurseForge
requires globally unique names and "Discord Presence" is taken there, so the
one word name stays. The slug stays `discordpresence` on both sites: changing
it breaks every existing link and the publisher's dependency mapping.)

## Summary field

Take control of your Discord Rich Presence (RPC) from in game. Click the live
Discord card to edit text, images and buttons, use 26 real time placeholders,
and set a custom Discord status per server. Client side only. Fabric, NeoForge
and Forge.

## Categories

Main category: Social. Additional: Utility. Social is where the Discord
integration mods live and where people browse for them; Cosmetic puts it next
to capes and animations.

## Page body

---

# Show your Minecraft session on Discord, exactly the way you want it

DiscordPresence is a Discord Rich Presence mod for Minecraft. Instead of config
files, you get a live editor: press **F6** (or the Config button in the mod
list) and the actual Discord card appears on screen. Click the text to change
the text. Click an image to pick an image. Click a button to add a link. What
you see is what Discord shows, updating as you type.

Client side only. Nothing to install on the server, and it works in
singleplayer, on any server, and in every menu.

## A custom Discord status without touching a config file

The editor shows the real card, not a form. Details line, state line, large
and small images with hover text, the timestamp, and up to two clickable
buttons (stream link, server IP, anything) are all edited in place. Presets
let you save a look and come back to it later, and profiles can be exported
and imported as JSON to share with friends.

## Different presence for menus, worlds and servers

- **Context profiles**: separate presence for the main menu, singleplayer and
  multiplayer, switched automatically as you play.
- **Per-server profiles**: give any server its own look, matched by address
  (for example `hypixel.net`).
- **Overrides**: change only what matters per dimension (Overworld, Nether,
  End, or any modded dimension) or per screen (title, server list, world
  list). Everything you do not touch inherits from the parent profile.

## 26 live placeholders

Use them in any text field and they update in real time: `{player}`,
`{server}`, `{dimension}`, `{biome}`, `{x}` `{y}` `{z}`, `{health}`,
`{hunger}`, `{armor}`, `{xp_level}`, `{gamemode}`, `{difficulty}`, `{online}`,
`{max_players}`, `{ping}`, `{fps}`, `{time}`, `{day}`, `{weather}`,
`{held_item}`, `{version}`, `{world}` and more.

## Custom images and privacy

Drop PNG files into `config/discordrpc/images/` and they show up in the image
picker, no Discord developer portal needed. One click hides your server IP or
coordinates from the card, and AFK detection can swap your status after a
configurable idle time.

## Discord not showing your Minecraft status?

Discord displays the activity of whichever app connected to it **first**.
Launchers with a built-in Discord status (the Modrinth App, GDLauncher, XMCL)
grab the connection before any mod can, so your custom presence is accepted
but never shown. DiscordPresence detects this and warns you in chat with the
exact fix: turn the launcher's Discord integration off, fully quit the
launcher (check the system tray), and launch again.

Two more things worth checking: Discord's own setting Activity Privacy, Share
your detected activities with others, must be on, and the Discord desktop app
must be running (the browser version cannot show rich presence from mods).

## Supported versions

| Minecraft | Loader |
|-----------|--------|
| 26.2 | NeoForge, Fabric |
| 26.1.x | NeoForge, Fabric |
| 1.21.11 | NeoForge |
| 1.21.1 | NeoForge |
| 1.20.1 to 1.20.4 | Forge |

Every version needs [ZambieLib](https://modrinth.com/mod/zambielib), and the
Fabric builds also need [Fabric API](https://modrinth.com/mod/fabric-api);
your launcher installs both automatically with the mod.
[Mod Menu](https://modrinth.com/mod/modmenu) is optional on Fabric and adds a
Configure button.

## FAQ

**Is this client side?** Yes, fully. Servers never see it.

**Does it work on multiplayer servers?** Yes, on any server. You can even give
each server its own presence.

**How is this different from other Discord RPC mods?** Most rich presence mods
are set up through config files or menus of text fields. DiscordPresence puts
the actual Discord card on screen and you click the part you want to change.
If you want per-server profiles and live placeholders without editing JSON,
that is what this mod is for.

**Does it reconnect if Discord restarts?** Yes, automatically, with backoff.
It also works with Flatpak and Snap installs of Discord on Linux.

**Where do I report a bug?** On the
[issue tracker](https://github.com/TinsleyDevers/DiscordRPC/issues).

## More from ZambieD

- [Wildlore](https://modrinth.com/mod/wildlore): every creature rolls its own
  size, sex and bloodline traits, with real pregnancies and a zoology journal.
- [Broadback](https://modrinth.com/mod/broadback): creatures are solid, stand
  on a cow and ride along.

[![BisectHosting](https://www.bisecthosting.com/partners/custom-banners/b756223b-dc97-441c-a066-87760affbeb1.webp)](https://bisecthosting.com/ATK)

*[Want to host your own server? My servers are hosted with BisectHosting. Use
promo code ATK for 25% off your first month.](https://bisecthosting.com/ATK)*
