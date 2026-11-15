# Spotify Controller

A client-side Fabric mod for **Minecraft 26.3** that connects to the Spotify Web API via OAuth 2.0 PKCE, renders an in-game **Now Playing** HUD widget, and lets you control Spotify playback without leaving Minecraft.

Because everything runs client-side, it works in singleplayer and on any multiplayer server without requiring server-side installation.

---

## Features

- **In-Game HUD Widget**
  - Displays the current track's album artwork, song title, artist name, elapsed/total time, and a smoothly interpolated progress bar.
  - Automatically scrolls long track and artist names (marquee) and hides when pressing `F1`.
  - Toggleable on/off from the config screen or via `/spotify hud`.
- **In-Game Configuration & OAuth 2.0 PKCE Auth**
  - Paste your Spotify Developer **Client ID** directly in-game (no Client Secret required).
  - One-click browser authorization with a local loopback callback (`http://127.0.0.1:8888/callback`) and automatic access token refreshes.
  - Persists configuration and tokens locally in `config/spotify_controller.json`.
- **Async Spotify Web API Client**
  - Polls playback state in the background without blocking Minecraft's tick or render threads.
  - Client commands for play, pause, next track, previous track, and playback status.

---

## Requirements

- **Minecraft:** `26.3`
- **Fabric Loader:** `>=0.19.0`
- **Fabric API:** `0.166.2+26.3` (or compatible `26.3` build)
- **Java:** `25`
- **Spotify Account:**
  - Viewing the currently playing track works on Free and Premium accounts.
  - Controlling playback (`/spotify play`, `pause`, `next`, `prev`) requires **Spotify Premium** (enforced by Spotify's Web API) and an active Spotify playback device.

---

## Setup Guide

1. Open the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) and create a new app (or open an existing one).
2. Under **Redirect URIs**, add:
   ```text
   http://127.0.0.1:8888/callback
   ```
   *(Make sure to use `127.0.0.1`, not `localhost`.)*
3. Copy your app's **Client ID**.
4. Place `spotify-controller-0.2.0.jar` and Fabric API into your Minecraft `mods/` folder and launch Minecraft `26.3`.
5. In-game, press **`O`** (or run `/spotify config`), paste your **Client ID**, and click **Save & Connect Spotify**.
6. Approve the permissions in the browser window that opens. Once redirected, return to Minecraft—your HUD widget and controls are ready!

---

## Keybinds & Commands

### Keybinds
| Default Key | Action | Category |
| :--- | :--- | :--- |
| `O` | Open Spotify Config / Status screen | Spotify Controller |

### Client Commands
| Command | Description |
| :--- | :--- |
| `/spotify config` | Open the in-game Spotify configuration screen |
| `/spotify clientid <id>` | Set and save your Spotify Client ID from chat |
| `/spotify login` | Start the browser OAuth PKCE login flow |
| `/spotify status` | Print connection state and currently playing track in chat |
| `/spotify hud` | Toggle the in-game Now Playing HUD widget on/off |
| `/spotify play` | Resume playback on your active Spotify device |
| `/spotify pause` | Pause playback on your active Spotify device |
| `/spotify next` | Skip to the next track |
| `/spotify prev` | Return to the previous track |

---

## Building from Source

Requires **JDK 25**.

```bash
# Linux / macOS
./gradlew build

# Windows PowerShell
.\gradlew.bat build
```

The compiled mod jar will be output to `build/libs/spotify-controller-0.2.0.jar`.
