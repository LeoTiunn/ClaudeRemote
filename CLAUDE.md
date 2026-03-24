# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Native Android app (Kotlin + Jetpack Compose) that connects via SSH to a remote server and pipes messages to/from a tmux session running the Claude CLI. The UI mirrors the Claude iOS app experience.

Remote server: `leo.chang@asune.asuscomm.com` (SSH)

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

- Full design specification: `2026-03-20-claude-remote-android-design.md`
- SSH connection persists in background with auto-reconnect
- tmux binary path and Claude CLI path are configurable
- Network loss: queue messages, send when reconnected
