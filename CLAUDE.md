# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Remote access to Claude CLI via tmux sessions. Three components:

1. **`server/`** — Server-side scripts (tmux+Claude session management, runs on remote host)
2. **`client/`** — Client-side scripts (TBD, will SSH to server)
3. **`android/`** — Android app (Kotlin + Jetpack Compose, connects via SSH)

Remote server: `leo.chang@asuscomm.com` (SSH)

## Project Structure

```
ClaudeRemote/
├── server/bin/      # tc, tcx, tcc, tccx, tcr, tcrx (deploy to ~/bin/)
├── client/bin/      # TBD
├── android/         # Android Gradle project
│   ├── app/
│   ├── core/        # ssh, tmux, ui modules
│   ├── features/    # chat, session, settings
│   └── gradle/
├── docs/
└── .github/workflows/
```

## Architecture

```
┌─────────────┐         SSH          ┌──────────────────┐         tmux
│  Android    │ ←───────────────→   │  Remote Server   │ ←──── claude CLI
│  App        │    (Apache MINA      │                  │
│             │     sshd library)     │                  │
└─────────────┘                     └──────────────────┘
```

### Key Components

- **SSH Client** — Apache MINA sshd for SSH connections
- **Session Manager** — Manages tmux session lifecycle (list, attach, send input, stream output)
- **Chat UI** — Jetpack Compose with streaming message list, input bar
- **Voice Input** — Android SpeechRecognizer API (free, on-device)
- **File Picker** — Android document/camera intents

### Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **SSH:** Apache MINA sshd
- **Storage:** EncryptedSharedPreferences (Android Keystore)
- **Voice:** Android SpeechRecognizer API
- **Networking:** Kotlin Coroutines + Flow
- **Architecture:** MVVM with Clean Architecture layers

## Features

### Chat UI
- Streaming responses with typing indicator
- Markdown rendering (code blocks, bold, italic)
- Copy/regenerate message buttons
- Attachments shown inline
- Light/dark/system theme

### Session Management
- Pull-down session switcher
- Lists all active tmux sessions with repo names
- Tap to switch, swipe to kill
- "New Session" → pick repo and create tmux session

### Voice Input
- Tap mic → Android speech recognition (free, on-device)
- Shows "Listening..." with waveform animation
- Tap again or pause to stop

### File Attachments
- Pick files from device or camera
- Images displayed inline
- Files uploaded to remote `~/Downloads/attachments/`
- Path sent to Claude CLI with message

### Security
- SSH password stored in Android Keystore via EncryptedSharedPreferences
- Auto-connects after first login

## Development Notes

- Android app is under `android/` — run `cd android && ./gradlew assembleDebug`
- Full design specification: `docs/plans/2026-03-20-claude-remote-android-design.md`
- SSH connection persists in background with auto-reconnect
- tmux binary path and Claude CLI path are configurable
- Network loss: queue messages, send when reconnected
- Server scripts: source of truth in `server/bin/`, deploy by copying to `~/bin/`
