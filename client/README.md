# ClaudeRemote Client

Client-side scripts that SSH into the remote server and run the server-side `tc` commands.

## Prerequisites

- **macOS** (uses Keychain for password storage)
- **sshpass** — `brew install esolitos/ipa/sshpass`
- **Server scripts deployed** — `tc`, `tcx`, etc. must be in `~/bin/` on the remote server

## Setup

### 1. Install sshpass

```sh
brew install esolitos/ipa/sshpass
```

### 2. Store SSH password in Keychain

```sh
security add-generic-password -s 'asune-ssh' -a 'leo.chang@asune.asuscomm.com' -w
```

You'll be prompted to enter the password.

### 3. Deploy client scripts

Copy scripts to `~/.local/bin/` (already in PATH):

```sh
cp client/bin/* ~/.local/bin/
```

### 4. Remove old tc function (if present)

If you have the old `tc()` function in `~/.zshrc`, remove it — shell functions shadow binaries of the same name. Then reload: `source ~/.zshrc`.

## Commands

| Command | Description |
|---------|-------------|
| `tc [project]` | New claude session |
| `tcx [project]` | New session, auto-approve permissions |
| `tcc [project]` | Continue last conversation |
| `tccx [project]` | Continue, auto-approve |
| `tcr [project]` | Resume (pick conversation) |
| `tcrx [project]` | Resume, auto-approve |

All commands accept an optional project name. Without it, they list active sessions.

## How it works

```
client/bin/tc [project]
  → tc-remote tc [project]
    → sshpass -e ssh -t remote ~/bin/tc [project]
      → tc-core (server) manages tmux + claude
```

The password is read from macOS Keychain at runtime — never stored in files.
