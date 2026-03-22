# ClaudeRemote

Remote access to Claude CLI via tmux sessions.

```
ClaudeRemote/
├── server/      # Server-side tmux+Claude session scripts
├── client/      # Client-side scripts (TBD)
└── android/     # Android app (Kotlin + Jetpack Compose)
```

## Server Scripts (`server/bin/`)

Session management commands that mirror Claude CLI aliases but wrap them in tmux. All commands share the same session-finding logic:

1. `tcx podz` — exact match active tmux session "podz" → attach
2. No match — lists all active sessions + fuzzy project matches under `~/Developer`
3. User picks an existing session or creates a new one with the appropriate Claude command

### Commands

| Command | Claude command in new session |
|---------|-------------------------------|
| `tc`    | `claude` |
| `tcx`   | `claude --dangerously-skip-permissions` |
| `tcc`   | `claude --continue` |
| `tccx`  | `claude --continue --dangerously-skip-permissions` |
| `tcr`   | `claude --resume` |
| `tcrx`  | `claude --resume --dangerously-skip-permissions` |

### Naming Convention

Prefix `t` = tmux-wrapped version of the corresponding Claude alias:

```
c   → tc       cx  → tcx
cc  → tcc      ccx → tccx
cr  → tcr      crx → tcrx
```

### Installation

Copy scripts to a directory in your PATH:

```bash
cp server/bin/* ~/bin/
```

## Client Scripts (`client/bin/`)

Same `tc*` commands but from a local Mac, connecting to the remote server via SSH.

Uses `sshpass` + macOS Keychain for passwordless SSH, then calls the server-side scripts over an interactive TTY.

| Command | Description |
|---------|-------------|
| `tc [project]` | New claude session |
| `tcx [project]` | New session, auto-approve permissions |
| `tcc [project]` | Continue last conversation |
| `tccx [project]` | Continue, auto-approve |
| `tcr [project]` | Resume (pick conversation) |
| `tcrx [project]` | Resume, auto-approve |

### Installation

```bash
cp client/bin/* ~/.local/bin/
```

See [`client/README.md`](client/README.md) for full setup (sshpass, Keychain).

## Android App (`android/`)

Native Android app that connects via SSH to the remote server and provides a terminal UI for Claude CLI through tmux.

### Features

- Full xterm.js terminal via WebView
- Session switcher (list, attach, kill tmux sessions)
- Voice input (on-device Android SpeechRecognizer)
- File attachments (pick/camera, upload via SCP)
- Auto-reconnect on network loss
- Light/dark/system theme

### Build

```bash
cd android
./gradlew assembleDebug
```

### Tech Stack

Kotlin, Jetpack Compose, Apache MINA sshd, EncryptedSharedPreferences, Coroutines + Flow
