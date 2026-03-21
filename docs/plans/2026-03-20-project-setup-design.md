# Project Setup Design

## Overview

Establish the Android project foundation with Gradle Kotlin DSL, Hilt DI, feature-based module structure, and GitHub Actions CI/CD.

---

## Tech Stack

| Component | Choice |
|-----------|--------|
| Build system | Gradle Kotlin DSL with version catalogs |
| Dependency injection | Hilt |
| Testing | JUnit + Mockk + Turbine (for Flow) |
| Navigation | Jetpack Navigation Compose |
| Min SDK | API 28 (Android 9.0) |
| Target SDK | Latest stable |

---

## Module Structure

```
app/
├── gradle/libs.versions.toml          # Version catalog (single source of truth)
├── build.gradle.kts                   # Root build config
├── settings.gradle.kts                # Project settings + module includes
├── .github/workflows/ci.yml           # GitHub Actions CI/CD
│
├── app/                               # Application shell module
│   └── src/main/...
│
├── core/
│   ├── core:ssh/                      # Apache MINA SSH client wrapper
│   ├── core:tmux/                     # tmux session management via SSH
│   └── core:ui/                       # Shared theme, components, typography
│
└── features/
    ├── features:chat/                 # Chat UI + streaming message handling
    ├── features:session/              # Session switcher UI
    └── features:settings/             # Settings screen + encrypted prefs
```

### Module Dependencies

```
app
├── core:ui
├── features:chat
├── features:session
└── features:settings

features:chat
├── core:ssh
├── core:tmux
└── core:ui

features:session
├── core:ssh
├── core:tmux
└── core:ui

features:settings
└── core:ui

core:tmux
└── core:ssh
```

---

## Key Design Decisions

### Feature Modules
Each feature (`features:*`) contains its own Clean Architecture layers:
- `ui/` — Composables + ViewModels
- `domain/` — Use cases + repository interfaces
- `data/` — Repository implementations

### Core Modules
- **core:ssh** — Wraps Apache MINA sshd, exposes `SshClient` interface
- **core:tmux** — Wraps tmux CLI commands over SSH, exposes `TmuxSessionManager`
- **core:ui** — App theme (Material 3), shared components (MarkdownRenderer, ConnectionStatusDot)

### Version Catalog
All dependencies declared in `gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.2.2"
kotlin = "1.9.22"
compose = "1.6.1"
hilt = "2.50"
mockk = "1.13.9"
turbine = "1.1.0"

[libraries]
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose" }
# ... etc

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

### CI/CD (GitHub Actions)
On every push to `main` and PRs:
1. Checkout code
2. Setup JDK 17
3. Run `./gradlew assembleDebug`
4. Run `./gradlew test`
5. Upload debug APK as artifact

---

## Next Steps

1. Create Gradle wrapper and project skeleton
2. Configure Hilt application class
3. Scaffold feature modules with build.gradle.kts
4. Setup GitHub Actions workflow
