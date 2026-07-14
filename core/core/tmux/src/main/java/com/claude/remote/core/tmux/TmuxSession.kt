package com.claude.remote.core.tmux

data class TmuxSession(
    /** tmux session name — the STABLE id used to attach/switch/kill. */
    val name: String,
    /** pane_current_path (the session's cwd). */
    val windowName: String,
    /** Claude Code's own conversation name (`/rename` sets it) — the display
     * source of truth. Empty when the session has no live claude process. */
    val claudeName: String = "",
    /** Claude Code busy/idle flag from ~/.claude/sessions/<pid>.json. */
    val status: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The session's working directory — same field webmux groups by. */
    val cwd: String get() = windowName

    /** What the UI shows: Claude Code conversation name (source of truth),
     * falling back to the tmux session name for non-Claude sessions. */
    val displayName: String get() = claudeName.ifEmpty { name }
}

/** A project groups the live tmux sessions sharing one cwd, exactly like the
 * webmux sidebar (project → sessions). Project label = last path segment of cwd. */
data class TmuxProject(
    val cwd: String,
    val sessions: List<TmuxSession>
) {
    /** e.g. /Users/x/Developer/leo-chang/webmux → "webmux". */
    val name: String get() = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
}

/** Group sessions into projects by cwd, preserving first-seen order (matches
 * iOS `groupedByProject` / webmux `buildProjects`). */
fun List<TmuxSession>.groupedByProject(): List<TmuxProject> {
    val order = ArrayList<String>()
    val byCwd = LinkedHashMap<String, MutableList<TmuxSession>>()
    for (s in this) {
        if (s.cwd !in byCwd) order.add(s.cwd)
        byCwd.getOrPut(s.cwd) { mutableListOf() }.add(s)
    }
    return order.map { TmuxProject(it, byCwd[it] ?: emptyList()) }
}
