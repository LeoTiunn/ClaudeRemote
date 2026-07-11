import json, os, glob, subprocess, sys
SESS = os.path.expanduser("~/.claude/sessions")

# Claude's own session registry, keyed by pid: name + status.
by_pid = {}
for f in glob.glob(SESS + "/*.json"):
    try:
        o = json.load(open(f))
    except Exception:
        continue
    p = o.get("pid")
    if p:
        by_pid[int(p)] = {"name": o.get("name") or "", "status": o.get("status") or ""}

# process table: pid -> (ppid, comm)
try:
    ps = subprocess.run(["ps", "-axo", "pid=,ppid=,comm="], capture_output=True, text=True).stdout
except Exception:
    ps = ""
kids = {}
comm = {}
for line in ps.splitlines():
    parts = line.split(None, 2)
    if len(parts) < 3:
        continue
    try:
        pid, ppid = int(parts[0]), int(parts[1])
    except ValueError:
        continue
    c = parts[2]
    kids.setdefault(ppid, []).append(pid)
    comm[pid] = c

def find_claude(root):
    stack = list(kids.get(root, []))
    seen = set()
    while stack:
        p = stack.pop()
        if p in seen:
            continue
        seen.add(p)
        cm = comm.get(p, "").lower()
        if ("claude" in cm or cm.endswith("node")) and p in by_pid:
            return p
        stack.extend(kids.get(p, []))
    return None

try:
    out = subprocess.run(
        ["tmux", "list-sessions", "-F", "#{session_name}\t#{pane_current_path}\t#{pane_pid}"],
        capture_output=True, text=True).stdout
except Exception:
    out = ""

for line in out.splitlines():
    cols = line.split("\t")
    if len(cols) < 3:
        continue
    name, cwd, pp = cols[0], cols[1], cols[2]
    cp = find_claude(int(pp)) if pp.isdigit() else None
    info = by_pid.get(cp) if cp else None
    cc_name = info["name"] if info else ""
    status = info["status"] if info else ""
    # tmux_name | cwd | cc_name | status   (pipe-delimited; names never contain '|')
    sys.stdout.write("%s|%s|%s|%s\n" % (name, cwd, cc_name, status))
