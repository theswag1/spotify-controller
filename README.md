# Spotify Controller

A client-side Fabric and NeoForge mod that connects to the Spotify Web API via OAuth 2.0 PKCE, renders a draggable in-game **Now Playing** HUD widget, and lets you control Spotify playback through keybinds, an interactive controller UI, and client commands.

Because everything runs client-side, it works in singleplayer and on any multiplayer server without requiring server-side installation.

---

## Features

- **In-Game 3-Step First-Time Setup Wizard** (`O`, `Z`, `/spotify setup`, or **Setup Guide** in Config)
  - Automatically opens the first time you press `O` or `Z` when no Spotify **Client ID** is configured, accompanied by a one-time yellow chat prompt when joining a world unconfigured.
  - Walks step-by-step through creating a Spotify Developer App (**Open Spotify Dashboard** button), registering the loopback Redirect URL (**Copy Redirect URL** button), and pasting/saving your **Client ID** (**Paste Clipboard** + **Save & Connect Spotify** buttons).
- **Full In-Game Controller Screen** (`Z`, `/spotify`, or `/spotify player`)
  - Displays `64x64` album art, marquee scrolling track / artist / album names, and a **clickable seek bar**.
  - Transport controls for **Shuffle**, **Previous**, **Play / Pause**, **Next**, and **Repeat** (`OFF` / `ALL` / `ONE`).
  - **Volume -10% / +10%** buttons plus an interactive **click-and-drag Volume bar**.
  - Quick buttons to toggle the HUD widget and open the **Config / Move HUD** screen.
- **Draggable, Resizable & Customizable HUD Widget**
  - Displays the current track's album art, song title, artist name, elapsed/total time, accent end strip, and smoothly interpolated progress bar.
  - **Drag-and-drop positioning** inside the Config screen (`O` or `/spotify config`) and **scroll-wheel resizing (`50%`–`200%` in `5%` steps)** while hovering over the HUD preview, plus a **Reset HUD** button.
  - **Customizable accent & playback bar color** via 8 color presets (`Green`, `Cyan`, `Blue`, `Purple`, `Pink`, `Red`, `Orange`, `White`) or a custom `#RRGGBB` hex input with a live color swatch.
  - Automatically scrolls long text (marquee), hides with `F1`, and toggles via keybind (`\`), Config/Controller buttons, or `/spotify hud`.
- **Configurable Keybinds**
  - Bindings for opening the Config/Controller UI, toggling the HUD, Play/Pause, Next Track, and Previous Track under **Options -> Controls -> Key Binds -> Spotify Controller**, showing clean action-bar feedback above the hotbar.
- **In-Game Configuration & OAuth 2.0 PKCE Auth**
  - Paste your Spotify Developer **Client ID** directly in-game (no Client Secret required).
  - One-click browser authorization with a local loopback callback (`http://127.0.0.1:8888/callback`), a **Copy Redirect URL** button, and automatic token refreshes stored locally in `config/spotify_controller_tokens.json` (kept separate from UI settings in `config/spotify_controller.json` so sharing your config never leaks your account tokens).
- **Async Spotify Web API Client**
  - Polls playback state and issues control requests asynchronously without blocking Minecraft's tick or render threads.

---

## Requirements

- **Minecraft:** `26.3`, `26.2`, or `26.1.x` (`26.1`–`26.1.2`)
- **Fabric:**
  - `spotify-controller-0.5.2-fabric-mc26.3.jar` (Fabric Loader `>=0.19.0`, Fabric API `0.166.2+26.3`)
  - `spotify-controller-0.5.2-fabric-mc26.2.jar` (Fabric Loader `>=0.19.0`, Fabric API `0.161.0+26.2`)
  - `spotify-controller-0.5.2-fabric-mc26.1.x.jar` (Fabric Loader `>=0.19.0`, Fabric API `0.155.3+26.1.2`)
- **NeoForge:**
  - `spotify-controller-0.5.2-neoforge-mc26.3.jar` (NeoForge `26.3.x`)
  - `spotify-controller-0.5.2-neoforge-mc26.2.jar` (NeoForge `26.2.x`)
  - `spotify-controller-0.5.2-neoforge-mc26.1.x.jar` (NeoForge `26.1.x`)
- **Java:** `25`
- **Spotify Account:**
  - Viewing the currently playing track works on Free and Premium accounts.
  - Controlling playback (play/pause, skip, seek, shuffle, repeat, volume) requires **Spotify Premium** (enforced by Spotify's Web API) and an active Spotify playback device.

---

## Setup Guide

1. Place the matching jar for your mod loader and Minecraft version into your `.minecraft/mods/` folder (on Fabric, also include **Fabric API**) and launch Minecraft. *(On NeoForge, you can also open the setup wizard/config from the **Mods -> Config** button.)*
2. Join a world—if no Client ID is set yet, you'll see a prompt in chat. Press **`O`** (or **`Z`**, or run `/spotify setup`) to open the **3-Step First-Time Setup Wizard** inside Minecraft:
   - **Step 1 — Create App:** Click **Open Spotify Dashboard**, sign in, click **Create app**, give it any name/description, and check **Web API**.
   - **Step 2 — Redirect URL:** Click **Copy Redirect URL** in-game to copy `http://127.0.0.1:8888/callback` *(use `127.0.0.1`, not `localhost`)*, paste it into **Redirect URIs**, click **Add**, accept the Terms of Service, and click **Save**.
   - **Step 3 — Connect:** Open **Settings** in your Spotify app, copy the **Client ID**, paste it into Minecraft (**Paste Clipboard**), and click **Save & Connect Spotify**.
3. Approve the permissions in the browser window that opens. Once redirected, click **Finish**—your HUD widget, keybinds, and controller screen (`Z`) are ready! *(You can reopen the wizard anytime via `/spotify setup` or the **Setup Guide** button in Config.)*

---

## Keybinds & Commands

### Keybinds
| Default Key | Action | Category |
| :--- | :--- | :--- |
| `O` | Open Spotify Config / Move HUD | Spotify Controller |
| `Z` | Open Spotify Controller | Spotify Controller |
| `\` | Toggle Spotify HUD | Spotify Controller |
| `K` | Play / Pause Track | Spotify Controller |
| `]` | Next Track | Spotify Controller |
| `[` | Previous Track | Spotify Controller |

### Client Commands
| Command | Description |
| :--- | :--- |
| `/spotify` or `/spotify player` | Open the interactive Spotify Controller screen (or Setup Wizard if Client ID is unset) |
| `/spotify config` | Open the Config / HUD positioning screen (or Setup Wizard if Client ID is unset) |
| `/spotify setup` | Open the interactive 3-step First-Time Setup Wizard |
| `/spotify clientid <id>` | Set and save your Spotify Client ID from chat |
| `/spotify login` | Start the browser OAuth PKCE login flow |
| `/spotify status` | Print connection state and currently playing track in chat |
| `/spotify hud` | Toggle the in-game Now Playing HUD widget on/off |
| `/spotify play` | Resume playback on your active Spotify device |
| `/spotify pause` | Pause playback on your active Spotify device |
| `/spotify next` | Skip to the next track |
| `/spotify prev` | Return to the previous track |
| `/spotify volume <0-100>` | Set playback volume percentage |
| `/spotify shuffle` | Toggle shuffle mode on/off |
| `/spotify repeat` | Cycle repeat mode (`OFF` -> `ALL` -> `ONE`) |

---

## Building from Source

Requires **JDK 25**.

```bash
# Fabric (Minecraft 26.3 / 26.2 / 26.1.x)
./gradlew build
./gradlew build -Pmc262
./gradlew build -Pmc261

# NeoForge (Minecraft 26.3 / 26.2 / 26.1.x)
./gradlew build -Pneoforge
./gradlew build -Pneoforge -Pmc262
./gradlew build -Pneoforge -Pmc261
```

*(On Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.)*

The compiled jars are output to `build/libs/`:
- `spotify-controller-0.5.2-fabric-mc26.3.jar` / `spotify-controller-0.5.2-fabric-mc26.2.jar` / `spotify-controller-0.5.2-fabric-mc26.1.x.jar` (Fabric)
- `spotify-controller-0.5.2-neoforge-mc26.3.jar` / `spotify-controller-0.5.2-neoforge-mc26.2.jar` / `spotify-controller-0.5.2-neoforge-mc26.1.x.jar` (NeoForge)
