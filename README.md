# ClaudeRemote

Remote access to Claude CLI via tmux sessions. Two components:

1. **Android App** — Kotlin/Jetpack Compose client that connects via SSH to a remote server and interacts with Claude through a tmux terminal
2. **Server Scripts** — tmux+Claude session management commands

## Server Scripts (`server/bin/`)

Session management commands that mirror the Claude CLI aliases but wrap them in tmux. All commands share the same session-finding logic:

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

Symlink scripts to a directory in your PATH:

```bash
for f in server/bin/*; do
  ln -sf "$(pwd)/$f" ~/bin/$(basename "$f")
done
```

## Android App

Native Android app (Kotlin + Jetpack Compose) that connects via SSH to the remote server and pipes input/output to a tmux session running Claude CLI.

### Features

- Full xterm.js terminal via WebView
- Session switcher (list, attach, kill tmux sessions)
- Voice input (on-device Android SpeechRecognizer)
- File attachments (pick/camera, upload via SCP)
- Auto-reconnect on network loss
- Light/dark/system theme

### Tech Stack

- Kotlin, Jetpack Compose, Apache MINA sshd, EncryptedSharedPreferences, Coroutines + Flow
