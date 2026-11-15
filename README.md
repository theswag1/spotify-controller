# Spotify Controller

A client-side Fabric mod for **Minecraft 26.3** that connects to the Spotify Web API via OAuth 2.0 PKCE, renders a draggable in-game **Now Playing** HUD widget, and lets you control Spotify playback through keybinds, an interactive controller UI, and client commands.

Because everything runs client-side, it works in singleplayer and on any multiplayer server without requiring server-side installation.

---

## Features

- **Full In-Game Controller Screen** (`Z`, `/spotify`, or `/spotify player`)
  - Displays `64x64` album art, marquee scrolling track / artist / album names, and a **clickable seek bar**.
  - Transport controls for **Shuffle**, **Previous**, **Play / Pause**, **Next**, and **Repeat** (`OFF` / `ALL` / `ONE`).
  - **Volume -10% / +10%** buttons plus an interactive **click-and-drag Volume bar**.
  - Quick buttons to toggle the HUD widget and open the **Config / Move HUD** screen.
- **Draggable In-Game HUD Widget**
  - Displays the current track's album art, song title, artist name, elapsed/total time, and smoothly interpolated progress bar.
  - **Drag-and-drop positioning** inside the Config screen (`O` or `/spotify config`) with live coordinate preview and a **Reset HUD Position** button.
  - Automatically scrolls long text (marquee), hides with `F1`, and toggles via keybind (`\`), Config/Controller buttons, or `/spotify hud`.
- **Configurable Keybinds**
  - Bindings for opening the Config/Controller UI, toggling the HUD, Play/Pause, Next Track, and Previous Track under **Options -> Controls -> Key Binds -> Spotify Controller**, showing clean action-bar feedback above the hotbar.
- **In-Game Configuration & OAuth 2.0 PKCE Auth**
  - Paste your Spotify Developer **Client ID** directly in-game (no Client Secret required).
  - One-click browser authorization with a local loopback callback (`http://127.0.0.1:8888/callback`), a **Copy Redirect URL** button, and automatic token refreshes stored locally in `config/spotify_controller.json`.
- **Async Spotify Web API Client**
  - Polls playback state and issues control requests asynchronously without blocking Minecraft's tick or render threads.

---

## Requirements

- **Minecraft:** `26.3`
- **Fabric Loader:** `>=0.19.0`
- **Fabric API:** `0.166.2+26.3` (or compatible `26.3` build)
- **Java:** `25`
- **Spotify Account:**
  - Viewing the currently playing track works on Free and Premium accounts.
  - Controlling playback (play/pause, skip, seek, shuffle, repeat, volume) requires **Spotify Premium** (enforced by Spotify's Web API) and an active Spotify playback device.

---

## Setup Guide

1. Open the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and create a new app (or open an existing one).
2. Under **Redirect URIs**, add:
   ```text
   http://127.0.0.1:8888/callback
   ```
   *(Make sure to use `127.0.0.1`, not `localhost`. You can also click **Copy Redirect URL** in-game.)*
3. Copy your app's **Client ID**.
4. Place `spotify-controller-0.4.0.jar` and Fabric API into your Minecraft `mods/` folder and launch Minecraft `26.3`.
5. In-game, press **`O`** (or run `/spotify config`), paste your **Client ID**, and click **Save & Connect Spotify**.
6. Approve the permissions in the browser window that opens. Once redirected, return to Minecraft—your HUD widget, keybinds, and controller screen (`Z`) are ready!

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
| `/spotify` or `/spotify player` | Open the interactive Spotify Controller screen |
| `/spotify config` | Open the Config / HUD positioning screen |
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
# Linux / macOS
./gradlew build

# Windows PowerShell
.\gradlew.bat build
```

The compiled mod jar will be output to `build/libs/spotify-controller-0.4.0.jar`.
