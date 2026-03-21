# Claude Remote Android App — Design

## Overview

Native Android app (Kotlin + Jetpack Compose) that connects via SSH to a remote server and pipes messages to/from a tmux session running the Claude CLI. The UI mirrors the Claude iOS app experience.

---

## Architecture

```
┌─────────────┐         SSH          ┌──────────────────┐         tmux
│  Android    │ ←───────────────→   │  Remote Server   │ ←──── claude CLI
│  App        │    (Apache MINA     │  (leo.chang@    │
│             │     sshd library)    │   asuscomm.com)  │
└─────────────┘                     └──────────────────┘
```

### Components

- **SSH Client** — `Apache MINA sshd` for SSH connections from Android
- **Session Manager** — Manages tmux session lifecycle (list, attach, send input, stream output)
- **Chat UI** — Jetpack Compose with streaming message list, input bar
- **Voice Input** — Android `SpeechRecognizer` API (free, on-device)
- **File Picker** — Android document/camera intents

---

## Features

### Chat UI
- Streaming responses with typing indicator
- Markdown rendering (code blocks, bold, italic)
- Copy message button
- Regenerate response button
- Attachments shown inline
- Light/dark/system theme

### Input Bar
- Text field with send button
- Voice input mic button
- Attachment button (file/camera)
- Character/token count

### Session Management
- Pull-down session switcher at top of screen
- Lists all active tmux sessions with repo names
- Tap to switch sessions
- Swipe to kill a session
- "New Session" button → pick a repo and create a new tmux session

### Voice Input
- Tap mic button → starts Android speech recognition (free, Google on-device)
- Shows "Listening..." with waveform animation
- Tap again or pause to stop → text appears in input field
- Tap send to submit to Claude

### File Attachments
- Pick files from device storage
- Camera photos
- Images displayed inline
- Files uploaded to remote (`~/Downloads/attachments/`)
- Path sent to Claude CLI with the message

### Connection & State
- Connecting (spinner)
- Connected (green dot)
- Reconnecting (yellow dot, auto-retry)
- Disconnected (red dot, manual reconnect)
- SSH connection persists in background
- Auto-reconnects if dropped

### Security
- SSH password stored in Android Keystore via EncryptedSharedPreferences
- First login prompts for password, auto-connects after

### Settings
- SSH server/port/username
- tmux binary path
- Claude CLI path
- Theme (light/dark/system)
- Font size
- Update/clear saved password

### Error Handling
- SSH failure → error with retry button
- tmux session died → prompt to restart session
- Network loss → queue messages, send when reconnected
- Claude error → show error message inline in chat

---

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **SSH:** Apache MINA sshd
- **Storage:** EncryptedSharedPreferences (Android Keystore)
- **Voice:** Android SpeechRecognizer API
- **Networking:** Kotlin Coroutines + Flow
- **Architecture:** MVVM with Clean Architecture layers
