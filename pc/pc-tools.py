"""Piggy Desk's tools on the user's Windows PC, as an MCP server over stdio.

Claude Code runs on the tablet; when a project lives on the PC, the tablet starts this over SSH
(`ssh piggy-pc python %USERPROFILE%\\.piggy-desk\\pc-tools.py --root <project>`) and uses these tools instead of its own
Bash/Read/Write/Edit/Glob/Grep, so they behave like those. The PC itself never talks to Anthropic.

    python pc-tools.py --root DIR [--mkdir]     serve MCP on stdin/stdout (JSON-RPC, one message per line, UTF-8)
    python pc-tools.py --context --root DIR     print one JSON object about the PC and the project, then exit

Standard library only (it runs on the PC's plain Python). The link is a ~1 s relay, so every tool does a whole job in one
call. Background jobs outlive the SSH session when Windows allows it; their logs and exit codes stay in
%USERPROFILE%\\.piggy-desk\\jobs (PIGGY_DESK_HOME overrides the folder).
"""

import argparse
import base64
import ctypes
import fnmatch
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

VERSION = "1"
PROTOCOLS = ("2024-11-05", "2025-03-26", "2025-06-18")
HOME = Path(os.environ.get("PIGGY_DESK_HOME") or Path.home() / ".piggy-desk")
JOBS = HOME / "jobs"
TMP = HOME / "tmp"
OUT_LIMIT = 20000          # characters of command output returned per call (MCP results are capped around 25k tokens)
READ_LIMIT = 80000         # characters of a text file returned per read
NO_WINDOW = 0x08000000     # CREATE_NO_WINDOW
NEW_GROUP = 0x00000200     # CREATE_NEW_PROCESS_GROUP
BREAKAWAY = 0x01000000     # CREATE_BREAKAWAY_FROM_JOB: let background jobs outlive the SSH session
IMAGE_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".gif": "image/gif", ".webp": "image/webp"}
SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venv", ".mypy_cache", ".pytest_cache"}
TYPES = {
    "py": [".py", ".pyi"], "js": [".js", ".mjs", ".cjs", ".jsx"], "ts": [".ts", ".tsx"], "java": [".java"],
    "kotlin": [".kt", ".kts"], "c": [".c", ".h"], "cpp": [".cpp", ".cc", ".cxx", ".hpp", ".hh", ".h"], "go": [".go"],
    "rust": [".rs"], "md": [".md", ".markdown"], "json": [".json"], "yaml": [".yaml", ".yml"], "toml": [".toml"],
    "html": [".html", ".htm"], "css": [".css", ".scss"], "sh": [".sh", ".bash"], "ps": [".ps1", ".psm1"],
    "txt": [".txt"], "csv": [".csv"], "xml": [".xml"], "sql": [".sql"], "cs": [".cs"], "ipynb": [".ipynb"],
}


def find_bash():
    for p in (os.environ.get("PIGGY_BASH"), r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files (x86)\Git\bin\bash.exe"):
        if p and os.path.isfile(p):
            return p
    git = shutil.which("git")
    if git:
        p = Path(git).resolve().parent.parent / "bin" / "bash.exe"
        if p.is_file():
            return str(p)
    return shutil.which("bash")


def find_rg():
    env = os.environ.get("PIGGY_RG")
    if env:
        return None if env == "none" else env
    rg = shutil.which("rg")
    if rg:
        return rg
    # VS Code ships one; its folder name changes with each version
    code = Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Microsoft VS Code"
    patterns = ["resources/app/node_modules/@vscode/ripgrep/bin/rg.exe",
                "*/resources/app/node_modules/@vscode/ripgrep/bin/rg.exe",
                "*/resources/app/node_modules.asar.unpacked/@vscode/ripgrep/bin/rg.exe",
                "*/resources/app/extensions/copilot/node_modules/@github/copilot/sdk/ripgrep/bin/win32-x64/rg.exe",
                "*/resources/app/node_modules.asar.unpacked/@github/*/ripgrep/bin/win32-x64/rg.exe"]
    for pat in patterns:
        for p in code.glob(pat):
            return str(p)
    return None


BASH = find_bash()
POWERSHELL = shutil.which("powershell") or r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
RG = None  # found on first grep


class ToolError(Exception):
    pass


# ---------------------------------------------------------------- paths, text

class State:
    root = Path.cwd()
    cwd = str(Path.cwd())
    lock = threading.Lock()


def resolve(p, base=None):
    """An absolute Windows path from C:\\x, C:/x, /c/x (Git Bash), ~ or a path relative to the project folder."""
    if p is None or str(p).strip() == "":
        return Path(base or State.root)
    s = os.path.expandvars(os.path.expanduser(str(p).strip()))
    m = re.match(r"^/([a-zA-Z])(/.*)?$", s)
    if m:
        s = m.group(1).upper() + ":" + (m.group(2) or "/")
    path = Path(s)
    if not path.is_absolute():
        path = Path(base or State.root) / path
    return Path(os.path.normpath(str(path)))


def decode(data):
    """Bytes from Windows programs: UTF-8 if it is, else the Chinese code page, line by line (outputs get mixed)."""
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        pass
    out = []
    for line in data.split(b"\n"):
        try:
            out.append(line.decode("utf-8"))
        except UnicodeDecodeError:
            out.append(line.decode("gbk", errors="replace"))
    return "\n".join(out)


def clip(text, limit=OUT_LIMIT):
    if len(text) <= limit:
        return text
    half = limit // 2
    return text[:half] + "\n\n… （中间省略 %d 个字符） …\n\n" % (len(text) - limit) + text[-half:]


class TextFile:
    """A text file's content plus how it was stored (encoding, BOM, line endings), so edits keep all three."""

    def __init__(self, path):
        self.path = path
        raw = path.read_bytes()
        if b"\0" in raw[:8192]:
            raise ToolError("%s 是二进制文件（%d 字节），不能当文本读写" % (path, len(raw)))
        self.bom = raw.startswith(b"\xef\xbb\xbf")
        body = raw[3:] if self.bom else raw
        self.lossy = False
        try:
            self.text = body.decode("utf-8")
            self.encoding = "utf-8"
        except UnicodeDecodeError:
            try:
                self.text = body.decode("gbk")
                self.encoding = "gbk"
            except UnicodeDecodeError:
                self.text = body.decode("utf-8", errors="replace")
                self.encoding = "utf-8"
                self.lossy = True  # neither UTF-8 nor GBK: readable, but saving would damage it
        crlf = self.text.count("\r\n")
        self.crlf = crlf > 0 and crlf == self.text.count("\n")
        if self.crlf:
            self.text = self.text.replace("\r\n", "\n")

    def save(self, text):
        if self.lossy:
            raise ToolError("%s 既不是 UTF-8 也不是 GBK 编码，改了会损坏它；用 bash/powershell 处理" % self.path)
        if self.crlf:
            text = text.replace("\r\n", "\n").replace("\n", "\r\n")
        try:
            data = text.encode(self.encoding)
        except UnicodeEncodeError:
            raise ToolError("%s 是 GBK 编码，新内容里有 GBK 表示不了的字符" % self.path)
        self.path.write_bytes((b"\xef\xbb\xbf" if self.bom else b"") + data)


def numbered(lines, first):
    return "\n".join("%6d\t%s" % (first + i, l if len(l) <= 2000 else l[:2000] + " …") for i, l in enumerate(lines))


# ---------------------------------------------------------------- processes

running = {}            # MCP request id -> Popen, so notifications/cancelled can stop it
running_lock = threading.Lock()


def kill_tree(pid):
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                   creationflags=NO_WINDOW)


def child_env():
    env = dict(os.environ)
    env.update({"PYTHONIOENCODING": "utf-8", "GIT_PAGER": "cat", "PAGER": "cat", "GIT_TERMINAL_PROMPT": "0", "TERM": "dumb"})
    return env


def sh_quote(s):
    return "'" + str(s).replace("'", "'\\''") + "'"


def ps_quote(s):
    return "'" + str(s).replace("'", "''") + "'"


def posix(p):
    return str(p).replace("\\", "/")


def script_for(shell, command, cwd, cwd_file=None, exit_file=None):
    """A script file's text that runs `command` in `cwd` and records where it ended up and/or its exit code."""
    if shell == "bash":
        lines = ["cd %s 2>/dev/null || cd ~" % sh_quote(posix(cwd))]
        if cwd_file:
            lines.append("trap 'pwd -W > %s 2>/dev/null' EXIT" % sh_quote(posix(cwd_file)))
        lines.append(command)
        if exit_file:
            lines.append("echo $? > %s" % sh_quote(posix(exit_file)))
        return "\n".join(lines) + "\n"
    head = ["[Console]::OutputEncoding = [System.Text.Encoding]::UTF8", "$OutputEncoding = [System.Text.Encoding]::UTF8",
            "$ProgressPreference = 'SilentlyContinue'", "Set-Location -LiteralPath %s" % ps_quote(cwd)]
    tail = []
    if cwd_file:
        tail.append("(Get-Location).ProviderPath | Out-File -LiteralPath %s -Encoding utf8" % ps_quote(cwd_file))
    code = "$code = if ($LASTEXITCODE) { $LASTEXITCODE } elseif ($ok) { 0 } else { 1 }"
    if exit_file:
        tail.append("Set-Content -LiteralPath %s -Value $code -Encoding ascii" % ps_quote(exit_file))
    return "\n".join(head + ["$ok = $true", "try {", command, "} catch { $ok = $false; Write-Output $_ } finally {", code]
                     + tail + ["}", "exit $code"]) + "\n"


def write_script(path, shell, text):
    if shell == "bash":
        path.write_bytes(text.encode("utf-8"))
    else:
        path.write_bytes(b"\xef\xbb\xbf" + text.replace("\n", "\r\n").encode("utf-8"))  # PowerShell 5.1 needs the BOM


def argv_for(shell, script):
    if shell == "bash":
        if not BASH:
            raise ToolError("这台电脑上找不到 Git Bash（C:\\Program Files\\Git\\bin\\bash.exe），改用 powershell 工具")
        return [BASH, str(script)]
    return [POWERSHELL, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(script)]


def run_shell(shell, command, timeout_ms, req_id):
    if not str(command or "").strip():
        raise ToolError("command 是空的")
    timeout = min(max(int(timeout_ms or 120000), 1000), 600000) / 1000.0
    TMP.mkdir(parents=True, exist_ok=True)
    tag = uuid.uuid4().hex[:12]
    script = TMP / ("%s.%s" % (tag, "sh" if shell == "bash" else "ps1"))
    cwd_file = TMP / (tag + ".cwd")
    start_dir = next(d for d in (State.cwd, str(State.root), str(Path.home())) if os.path.isdir(d))
    write_script(script, shell, script_for(shell, command, start_dir, cwd_file=cwd_file))
    chunks = []
    try:
        proc = subprocess.Popen(argv_for(shell, script), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, cwd=start_dir, env=child_env(), creationflags=NO_WINDOW)
        with running_lock:
            running[req_id] = proc
        # read1: whatever has arrived, so output isn't lost when something the command started keeps the pipe open
        reader = threading.Thread(target=lambda: chunks.extend(iter(lambda: proc.stdout.read1(65536), b"")), daemon=True)
        reader.start()
        timed_out = False
        try:
            code = proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            kill_tree(proc.pid)
            code = proc.wait(timeout=10)
        reader.join(timeout=5)
        cancelled = getattr(proc, "cancelled", False)
    finally:
        with running_lock:
            running.pop(req_id, None)
        try:
            new_cwd = cwd_file.read_text(encoding="utf-8-sig").strip()
            if new_cwd and os.path.isdir(new_cwd):
                State.cwd = os.path.normpath(new_cwd)
        except OSError:
            pass
        for f in (script, cwd_file):
            try:
                f.unlink()
            except OSError:
                pass
    out = clip(decode(b"".join(chunks)).replace("\r\n", "\n").rstrip("\n"))
    notes = []
    if timed_out:
        notes.append("超时（%d 秒），已结束这个命令和它启动的进程。长任务用 bg_start。" % timeout)
    elif cancelled:
        notes.append("已取消。")
    if code and not timed_out:
        notes.append("退出码 %d" % code)
    text = out if out else "（没有输出）"
    if notes:
        text += "\n\n" + "\n".join(notes)
    return text, bool(code) or timed_out


# ---------------------------------------------------------------- tools

def t_bash(a, rid):
    text, failed = run_shell("bash", a.get("command"), a.get("timeout_ms") or a.get("timeout"), rid)
    return [txt(text)], failed


def t_powershell(a, rid):
    text, failed = run_shell("powershell", a.get("command"), a.get("timeout_ms") or a.get("timeout"), rid)
    return [txt(text)], failed


def t_read(a, rid):
    path = resolve(a.get("file_path"))
    if not path.exists():
        raise ToolError("文件不存在：%s" % path)
    if path.is_dir():
        raise ToolError("%s 是文件夹，用 ls 列出内容" % path)
    ext = path.suffix.lower()
    if ext in IMAGE_TYPES:
        data = path.read_bytes()
        if len(data) > 10 * 1024 * 1024:
            raise ToolError("图片太大（%d MB），读不了" % (len(data) // 1048576))
        return [{"type": "image", "data": base64.b64encode(data).decode("ascii"), "mimeType": IMAGE_TYPES[ext]},
                txt("%s（%d 字节）" % (path, len(data)))], False
    if ext == ".pdf":
        raise ToolError("PDF 读不了：可以用 bash 跑 pdftotext 或 Python 提取文字")
    f = TextFile(path)
    if f.text == "":
        return [txt("（%s 是空文件）" % path)], False
    lines = f.text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    offset = max(int(a.get("offset") or 1), 1)
    limit = max(int(a.get("limit") or 2000), 1)
    part = lines[offset - 1: offset - 1 + limit]
    if not part:
        raise ToolError("文件只有 %d 行，offset %d 超出了" % (len(lines), offset))
    out = numbered(part, offset)
    note = ""
    if len(out) > READ_LIMIT:
        out = out[:READ_LIMIT]
        out = out[:out.rfind("\n")]
        shown = out.count("\n") + 1
        note = "\n\n（太长，只给了第 %d–%d 行；用 offset/limit 读剩下的）" % (offset, offset + shown - 1)
    elif offset - 1 + limit < len(lines):
        note = "\n\n（共 %d 行，这里是第 %d–%d 行）" % (len(lines), offset, offset + len(part) - 1)
    return [txt(out + note)], False


def t_write(a, rid):
    path = resolve(a.get("file_path"))
    content = a.get("content")
    if content is None:
        raise ToolError("缺少 content")
    if path.is_dir():
        raise ToolError("%s 是文件夹" % path)
    path.parent.mkdir(parents=True, exist_ok=True)
    kept = False
    verb = "已覆盖" if path.exists() else "已新建"
    if path.exists():
        try:
            TextFile(path).save(content)  # keep the file's encoding, BOM and line endings
            kept = True
        except ToolError:
            pass                          # not text we can keep as it was: replace it
    if not kept:
        # Windows PowerShell 5.1 misreads UTF-8 scripts without a BOM when they contain non-ASCII text
        bom = path.suffix.lower() in (".ps1", ".psm1", ".psd1") and any(ord(c) > 127 for c in content)
        path.write_bytes((b"\xef\xbb\xbf" if bom else b"") + content.encode("utf-8"))
    n = content.count("\n") + (0 if content.endswith("\n") or content == "" else 1)
    return [txt("%s %s（%d 行）" % (verb, path, n))], False


def t_edit(a, rid):
    path = resolve(a.get("file_path"))
    old = a.get("old_string")
    new = a.get("new_string")
    if old is None or new is None:
        raise ToolError("需要 old_string 和 new_string")
    if old == new:
        raise ToolError("old_string 和 new_string 一样，没有要改的")
    if old == "":
        if path.exists() and path.stat().st_size > 0:
            raise ToolError("old_string 为空只能用来新建文件，而 %s 已经有内容" % path)
        return t_write({"file_path": str(path), "content": new}, rid)
    if not path.exists():
        raise ToolError("文件不存在：%s" % path)
    f = TextFile(path)
    old_n = old.replace("\r\n", "\n") if f.crlf else old
    new_n = new.replace("\r\n", "\n") if f.crlf else new
    count = f.text.count(old_n)
    if count == 0:
        hint = ""
        if re.sub(r"\s+", " ", old_n).strip() in re.sub(r"\s+", " ", f.text):
            hint = "（忽略空白后能找到：缩进或空格不一致，先 read 看准原文）"
        raise ToolError("在 %s 里找不到 old_string%s" % (path, hint))
    if count > 1 and not a.get("replace_all"):
        raise ToolError("old_string 在文件里出现了 %d 次：多带几行上下文让它唯一，或者用 replace_all" % count)
    at = f.text.index(old_n)
    text = f.text.replace(old_n, new_n) if a.get("replace_all") else f.text.replace(old_n, new_n, 1)
    f.save(text)
    lines = text.split("\n")
    first = f.text.count("\n", 0, at)
    lo, hi = max(first - 3, 0), min(first + new_n.count("\n") + 4, len(lines))
    head = "已修改 %s%s" % (path, "（替换了 %d 处）" % count if a.get("replace_all") else "")
    return [txt(head + "\n" + numbered(lines[lo:hi], lo + 1))], False


def t_glob(a, rid):
    pattern = str(a.get("pattern") or "").strip()
    if not pattern:
        raise ToolError("缺少 pattern")
    base = resolve(a.get("path"))
    pattern = pattern.replace("\\", "/")
    if re.match(r"^([a-zA-Z]:)?/", pattern):  # an absolute pattern: its fixed leading folders become the base
        parts = pattern.split("/")
        k = next((i for i, x in enumerate(parts) if re.search(r"[*?\[]", x)), len(parts) - 1)
        base = resolve("/".join(parts[:k]) or "/")
        pattern = "/".join(parts[k:])
    if not base.is_dir():
        raise ToolError("不是文件夹：%s" % base)
    found = []
    for p in base.glob(pattern):
        try:
            if ".git" in p.relative_to(base).parts:
                continue
        except ValueError:
            pass
        if p.is_file():
            try:
                found.append((p.stat().st_mtime, str(p)))
            except OSError:
                pass
        if len(found) > 20000:
            break
    if not found:
        return [txt("没有匹配 %s 的文件（在 %s 下）" % (pattern, base))], False
    found.sort(reverse=True)
    more = len(found) - 1000
    out = "\n".join(p for _, p in found[:1000])
    if more > 0:
        out += "\n\n（还有 %d 个没列出，换个更具体的 pattern）" % more
    return [txt(out)], False


def expand_braces(pat):
    m = re.search(r"\{([^{}]*)\}", pat)
    if not m:
        return [pat]
    return [x for alt in m.group(1).split(",") for x in expand_braces(pat[:m.start()] + alt + pat[m.end():])]


def t_grep(a, rid):
    global RG
    pattern = a.get("pattern")
    if not pattern:
        raise ToolError("缺少 pattern")
    base = resolve(a.get("path"))
    if not base.exists():
        raise ToolError("路径不存在：%s" % base)
    mode = a.get("output_mode") or "files_with_matches"
    head = int(a.get("head_limit") or 250)
    if RG is None:
        RG = find_rg() or ""
    lines = grep_rg(a, pattern, base, mode, rid) if RG else grep_py(a, pattern, base, mode)
    if lines is None:
        lines = grep_py(a, pattern, base, mode)
    if not lines:
        return [txt("没有匹配")], False
    out = "\n".join(lines[:head])
    if len(lines) > head:
        out += "\n\n（只显示前 %d 条，共 %d 条；用 head_limit 或更具体的 path/glob）" % (head, len(lines))
    return [txt(clip(out))], False


def grep_rg(a, pattern, base, mode, rid):
    args = [RG, "--no-heading", "--with-filename", "--color", "never", "--max-columns", "500", "--max-columns-preview"]
    if a.get("-i"):
        args.append("-i")
    if mode == "files_with_matches":
        args.append("-l")
    elif mode == "count":
        args.append("-c")
    else:
        if a.get("-n", True):
            args.append("-n")
        for k in ("-A", "-B", "-C"):
            if a.get(k) is not None:
                args += [k, str(int(a[k]))]
    if a.get("glob"):
        args += ["--glob", str(a["glob"])]
    if a.get("type"):
        args += ["--type", str(a["type"])]
    if a.get("multiline"):
        args += ["-U", "--multiline-dotall"]
    args += ["-e", str(pattern), "--", str(base)]
    try:
        r = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120,
                           creationflags=NO_WINDOW)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if r.returncode == 2 and not r.stdout:
        err = decode(r.stderr).strip()
        if "regex" in err.lower():
            raise ToolError("正则写法不对：" + err[:500])
        return None
    out = decode(r.stdout).replace("\r\n", "\n").rstrip("\n")
    lines = out.split("\n") if out else []
    if mode == "files_with_matches":
        def mtime(p):
            try:
                return os.path.getmtime(p)
            except OSError:
                return 0
        lines.sort(key=mtime, reverse=True)
    return lines


def grep_py(a, pattern, base, mode):
    flags = re.IGNORECASE if a.get("-i") else 0
    if a.get("multiline"):
        flags |= re.MULTILINE | re.DOTALL
    try:
        rx = re.compile(pattern, flags)
    except re.error as e:
        raise ToolError("正则写法不对：%s" % e)
    globs = expand_braces(str(a["glob"])) if a.get("glob") else None
    exts = TYPES.get(str(a.get("type") or ""), None)
    ctx_a = int(a.get("-A") or a.get("-C") or 0)
    ctx_b = int(a.get("-B") or a.get("-C") or 0)
    files = [base] if base.is_file() else []
    if base.is_dir():
        for d, dirs, names in os.walk(base):
            dirs[:] = [x for x in dirs if x not in SKIP_DIRS and not x.startswith(".")]
            for n in names:
                files.append(Path(d) / n)
    out = []
    for p in files:
        rel = posix(p.relative_to(base)) if p != base else p.name
        if globs and not any(fnmatch.fnmatch(p.name, g) or fnmatch.fnmatch(rel, g) for g in globs):
            continue
        if exts and p.suffix.lower() not in exts:
            continue
        try:
            if p.stat().st_size > 20 * 1024 * 1024:
                continue
            text = TextFile(p).text
        except (ToolError, OSError):
            continue
        if a.get("multiline"):
            hits = [text.count("\n", 0, m.start()) for m in rx.finditer(text)]
        else:
            hits = [i for i, l in enumerate(text.split("\n")) if rx.search(l)]
        if not hits:
            continue
        if mode == "files_with_matches":
            out.append((p.stat().st_mtime, str(p)))
        elif mode == "count":
            out.append((0, "%s:%d" % (p, len(hits))))
        else:
            lines = text.split("\n")
            show = sorted({j for i in hits for j in range(max(i - ctx_b, 0), min(i + ctx_a + 1, len(lines)))})
            hit = set(hits)
            for j in show:
                num = (":%d:" % (j + 1)) if a.get("-n", True) else ":"
                if j not in hit:
                    num = num.replace(":", "-")
                out.append((0, "%s%s%s" % (p, num, lines[j][:500])))
    if mode == "files_with_matches":
        out.sort(reverse=True)
    return [x for _, x in out]


def t_ls(a, rid):
    path = resolve(a.get("path"))
    if not path.is_dir():
        raise ToolError("不是文件夹：%s" % path)
    entries = []
    for e in os.scandir(path):
        try:
            entries.append((not e.is_dir(), e.name.lower(), e.name + ("/" if e.is_dir() else ""),
                            "" if e.is_dir() else size_text(e.stat().st_size)))
        except OSError:
            continue
    entries.sort()
    lines = ["%s%s" % (name, "  (" + size + ")" if size else "") for _, _, name, size in entries[:1000]]
    more = len(entries) - 1000
    out = str(path) + "\n" + ("\n".join(lines) if lines else "（空文件夹）")
    if more > 0:
        out += "\n…还有 %d 项" % more
    return [txt(out)], False


def size_text(n):
    return "%d B" % n if n < 1024 else "%.1f KB" % (n / 1024) if n < 1048576 else "%.1f MB" % (n / 1048576)


# ---------------------------------------------------------------- background jobs

jobs_lock = threading.Lock()
read_offsets = {}       # job id -> bytes of its log already returned by bg_output


def jobs_index():
    try:
        return json.loads((JOBS / "jobs.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def save_jobs(jobs):
    JOBS.mkdir(parents=True, exist_ok=True)
    tmp = JOBS / "jobs.json.tmp"
    tmp.write_text(json.dumps(jobs[-200:], ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, JOBS / "jobs.json")


def pid_alive(pid):
    k32 = ctypes.windll.kernel32
    h = k32.OpenProcess(0x1000, False, int(pid))  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = ctypes.c_ulong()
    ok = k32.GetExitCodeProcess(h, ctypes.byref(code))
    k32.CloseHandle(h)
    return bool(ok) and code.value == 259  # STILL_ACTIVE


def job_status(j):
    exit_file = Path(j["exit_file"])
    if exit_file.exists():
        v = exit_file.read_text(encoding="utf-8-sig", errors="replace").strip()
        return "已结束（被 bg_kill 停止）" if v == "killed" else "已结束，退出码 %s" % (v or "?")
    if pid_alive(j["pid"]):
        return "运行中（%s）" % duration(time.time() - j["started"])
    return "已结束（退出码不明）"


def duration(s):
    s = int(s)
    return "%d 秒" % s if s < 60 else "%d 分 %d 秒" % (s // 60, s % 60) if s < 3600 else "%d 小时 %d 分" % (s // 3600, s % 3600 // 60)


def find_job(jid):
    for j in jobs_index():
        if j["id"] == jid:
            return j
    raise ToolError("没有这个后台任务：%s（bg_list 看全部）" % jid)


def t_bg_start(a, rid):
    command = str(a.get("command") or "")
    if not command.strip():
        raise ToolError("command 是空的")
    shell = "powershell" if a.get("shell") == "powershell" else "bash"
    cwd = str(resolve(a.get("cwd"), State.cwd)) if a.get("cwd") else (State.cwd if os.path.isdir(State.cwd) else str(State.root))
    if not os.path.isdir(cwd):
        raise ToolError("文件夹不存在：%s" % cwd)
    JOBS.mkdir(parents=True, exist_ok=True)
    with jobs_lock:
        jobs = jobs_index()
        n = max([int(j["id"][1:]) for j in jobs if re.match(r"^j\d+$", j["id"])] + [0]) + 1
        jid = "j%d" % n
        log = JOBS / (jid + ".log")
        exit_file = JOBS / (jid + ".exit")
        script = JOBS / ("%s.%s" % (jid, "sh" if shell == "bash" else "ps1"))
        write_script(script, shell, script_for(shell, command, cwd, exit_file=exit_file))
        for f in (log, exit_file):
            if f.exists():
                f.unlink()
        out = open(log, "wb")
        try:
            argv = argv_for(shell, script)
            try:
                proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, cwd=cwd,
                                        env=child_env(), creationflags=NO_WINDOW | NEW_GROUP | BREAKAWAY)
            except OSError:  # the SSH session's job object doesn't allow breaking away: the job ends with the session
                proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT, cwd=cwd,
                                        env=child_env(), creationflags=NO_WINDOW | NEW_GROUP)
        finally:
            out.close()
        jobs.append({"id": jid, "command": command, "shell": shell, "cwd": cwd, "pid": proc.pid, "started": time.time(),
                     "log": str(log), "exit_file": str(exit_file), "description": a.get("description") or ""})
        save_jobs(jobs)
    return [txt("已在后台启动 %s（pid %d）\n日志：%s\n用 bg_output 看输出，bg_kill 停止。" % (jid, proc.pid, log))], False


def t_bg_output(a, rid):
    j = find_job(str(a.get("id") or ""))
    log = Path(j["log"])
    data = log.read_bytes() if log.exists() else b""
    status = job_status(j)
    if a.get("tail_lines"):
        text = "\n".join(decode(data).replace("\r\n", "\n").rstrip("\n").split("\n")[-int(a["tail_lines"]):])
    else:
        start = read_offsets.get(j["id"], 0)
        if start > len(data):
            start = 0
        text = decode(data[start:]).replace("\r\n", "\n").rstrip("\n")
        read_offsets[j["id"]] = len(data)
        if not text:
            text = "（上次之后没有新输出）"
    return [txt("%s · %s\n\n%s" % (j["id"], status, clip(text)))], False


def t_bg_kill(a, rid):
    j = find_job(str(a.get("id") or ""))
    if Path(j["exit_file"]).exists():
        return [txt("%s 已经结束了：%s" % (j["id"], job_status(j)))], False
    kill_tree(j["pid"])
    Path(j["exit_file"]).write_text("killed", encoding="utf-8")
    return [txt("已停止 %s" % j["id"])], False


def t_bg_list(a, rid):
    jobs = jobs_index()[-20:]
    if not jobs:
        return [txt("没有后台任务")], False
    rows = ["%s · %s · %s\n    %s" % (j["id"], job_status(j), time.strftime("%m-%d %H:%M", time.localtime(j["started"])),
                                      j["command"].split("\n")[0][:200]) for j in reversed(jobs)]
    return [txt("\n".join(rows))], False


def txt(s):
    return {"type": "text", "text": s}


# ---------------------------------------------------------------- tool list

def schema(props, required=()):
    return {"type": "object", "properties": props, "required": list(required), "additionalProperties": False}


S_STR = {"type": "string"}
S_INT = {"type": "integer"}
S_BOOL = {"type": "boolean"}

TOOLS = [
    ("bash", t_bash, False,
     "Runs a command in Git Bash on the user's Windows PC (not on the tablet). The working directory persists between "
     "calls and starts at the project folder. stdin is closed (nothing interactive). Output (stdout+stderr) is cut to "
     "about 20000 characters. Default timeout 2 minutes, max 10; for anything longer use bg_start. Windows paths work "
     "(C:/Users/...). Use the read/write/edit/glob/grep tools rather than cat/sed/find/grep.",
     schema({"command": S_STR, "timeout_ms": dict(S_INT, description="Timeout in ms, max 600000"),
             "description": dict(S_STR, description="What this command does, in a few words")}, ["command"])),
    ("powershell", t_powershell, False,
     "Runs Windows PowerShell 5.1 on the user's Windows PC, in the same working directory as bash. For Windows-only "
     "things (services, registry, Get-CimInstance, Windows paths with spaces). Output UTF-8, cut to about 20000 characters.",
     schema({"command": S_STR, "timeout_ms": S_INT, "description": S_STR}, ["command"])),
    ("read", t_read, True,
     "Reads a file on the user's Windows PC. Text comes back like cat -n (line numbers), 2000 lines by default; use "
     "offset (first line, 1-based) and limit for more. Images (png/jpg/gif/webp) come back as images. Paths: absolute "
     "Windows paths, or relative to the project folder.",
     schema({"file_path": S_STR, "offset": S_INT, "limit": S_INT}, ["file_path"])),
    ("write", t_write, False,
     "Writes a whole file on the user's Windows PC, creating folders as needed. Overwriting keeps the file's encoding, "
     "BOM and line endings. Read a file before overwriting it; prefer edit for changes.",
     schema({"file_path": S_STR, "content": S_STR}, ["file_path", "content"])),
    ("edit", t_edit, False,
     "Replaces exact text in a file on the user's Windows PC. old_string must match exactly (indentation included) and be "
     "unique, unless replace_all. Line endings (CRLF), encoding and BOM are kept. Read the file first. An empty "
     "old_string creates a new file.",
     schema({"file_path": S_STR, "old_string": S_STR, "new_string": S_STR, "replace_all": S_BOOL},
            ["file_path", "old_string", "new_string"])),
    ("glob", t_glob, True,
     "Finds files on the user's Windows PC by pattern (e.g. **/*.py), newest first, at most 1000. path defaults to the "
     "project folder.",
     schema({"pattern": S_STR, "path": S_STR}, ["pattern"])),
    ("grep", t_grep, True,
     "Searches file contents on the user's Windows PC with a regular expression (ripgrep syntax). output_mode: "
     "files_with_matches (default), content (matching lines; -n, -A, -B, -C apply) or count. glob filters files "
     "(e.g. *.py or *.{ts,tsx}), type by language (py, js, ts, java, ...). Respects .gitignore.",
     schema({"pattern": S_STR, "path": S_STR, "glob": S_STR, "type": S_STR,
             "output_mode": {"type": "string", "enum": ["files_with_matches", "content", "count"]},
             "-i": S_BOOL, "-n": S_BOOL, "-A": S_INT, "-B": S_INT, "-C": S_INT, "head_limit": S_INT, "multiline": S_BOOL},
            ["pattern"])),
    ("ls", t_ls, True,
     "Lists a folder on the user's Windows PC (folders first, with file sizes). path defaults to the project folder.",
     schema({"path": S_STR})),
    ("bg_start", t_bg_start, False,
     "Starts a long command in the background on the user's Windows PC (training, builds, servers) and returns a job id "
     "right away. Output goes to a log; check it with bg_output, stop it with bg_kill. Jobs keep running if the "
     "connection drops. shell: bash (default) or powershell. Follow the user's rules for long tasks (CLAUDE.md).",
     schema({"command": S_STR, "shell": {"type": "string", "enum": ["bash", "powershell"]}, "cwd": S_STR,
             "description": S_STR}, ["command"])),
    ("bg_output", t_bg_output, True,
     "Shows a background job's status and the output it printed since the last bg_output (or its last tail_lines lines).",
     schema({"id": S_STR, "tail_lines": S_INT}, ["id"])),
    ("bg_kill", t_bg_kill, False,
     "Stops a background job and everything it started.",
     schema({"id": S_STR}, ["id"])),
    ("bg_list", t_bg_list, True,
     "Lists recent background jobs on the user's Windows PC with their status.",
     schema({})),
]
TOOL_FUNCS = {name: (fn, ro) for name, fn, ro, _, _ in TOOLS}


def tools_list():
    out = []
    for name, _, ro, desc, sch in TOOLS:
        out.append({"name": name, "description": desc, "inputSchema": sch,
                    "annotations": {"readOnlyHint": ro, "destructiveHint": not ro, "openWorldHint": False}})
    return out


# ---------------------------------------------------------------- MCP server

out_lock = threading.Lock()


def send(msg):
    data = (json.dumps(msg, ensure_ascii=False) + "\n").encode("utf-8")
    with out_lock:
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()


def instructions():
    return ("These tools run on the user's Windows PC (%s), reached from the tablet over SSH. The project folder is %s. "
            "Use them for everything in this project: files, commands, the GPU. bash is Git Bash; powershell is Windows "
            "PowerShell 5.1. Long jobs go through bg_start." % (socket.gethostname(), State.root))


def handle(msg):
    mid = msg.get("id")
    method = msg.get("method")
    params = msg.get("params") or {}
    if method == "initialize":
        want = params.get("protocolVersion")
        send({"jsonrpc": "2.0", "id": mid, "result": {
            "protocolVersion": want if want in PROTOCOLS else PROTOCOLS[-1],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "pc", "version": VERSION},
            "instructions": instructions()}})
    elif method == "ping":
        send({"jsonrpc": "2.0", "id": mid, "result": {}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": mid, "result": {"tools": tools_list()}})
    elif method == "tools/call":
        name = params.get("name")
        args = params.get("arguments") or {}
        if name not in TOOL_FUNCS:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32602, "message": "unknown tool: %s" % name}})
            return
        try:
            content, failed = TOOL_FUNCS[name][0](args, mid)
            result = {"content": content}
            if failed:
                result["isError"] = True
        except ToolError as e:
            result = {"content": [txt(str(e))], "isError": True}
        except Exception as e:  # a bug or an OS error: report it, keep serving
            result = {"content": [txt("%s 出错：%s: %s" % (name, type(e).__name__, e))], "isError": True}
        send({"jsonrpc": "2.0", "id": mid, "result": result})
    elif method == "notifications/cancelled":
        with running_lock:
            proc = running.get(params.get("requestId"))
        if proc is not None:
            proc.cancelled = True
            kill_tree(proc.pid)
    elif method in ("resources/list", "prompts/list"):
        send({"jsonrpc": "2.0", "id": mid, "result": {method.split("/")[0]: []}})
    elif mid is not None:
        send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found: %s" % method}})


def serve():
    if os.name == "nt":
        import msvcrt
        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
        msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)
    pool = ThreadPoolExecutor(max_workers=8)
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line.decode("utf-8"))
        except ValueError:
            send({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            continue
        if isinstance(msg, dict) and msg.get("method") in ("initialize", "ping", "notifications/cancelled", "tools/list"):
            handle(msg)  # quick ones answer in order
        elif isinstance(msg, dict):
            pool.submit(handle, msg)
    # the tablet went away: stop the commands still running in the foreground (background jobs keep going) and leave
    # without waiting for the worker threads
    with running_lock:
        procs = list(running.values())
    for proc in procs:
        kill_tree(proc.pid)
    os._exit(0)


# ---------------------------------------------------------------- --context

def context():
    root = State.root
    info = {"hostname": socket.gethostname(), "windows": platform.platform(), "user": os.environ.get("USERNAME", ""),
            "python": sys.version.split()[0], "root": str(root), "root_exists": root.is_dir(),
            "tools": {k: shutil.which(k) for k in ("git", "torchpy", "torchpip", "node", "nvidia-smi", "uv")},
            "bash": BASH, "rg": find_rg(), "gpu": [], "git_branch": "", "claude_md": []}
    try:
        r = subprocess.run(["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"], capture_output=True,
                           timeout=15, creationflags=NO_WINDOW)
        info["gpu"] = [l.strip() for l in decode(r.stdout).splitlines() if l.strip()]
    except (OSError, subprocess.TimeoutExpired):
        pass
    if root.is_dir() and info["tools"]["git"]:
        try:
            r = subprocess.run(["git", "-C", str(root), "rev-parse", "--abbrev-ref", "HEAD"], capture_output=True, timeout=15,
                               creationflags=NO_WINDOW)
            if r.returncode == 0:
                info["git_branch"] = decode(r.stdout).strip()
        except (OSError, subprocess.TimeoutExpired):
            pass
    # the same memory files Claude Code would read if it ran here: the user's global one, then the project's folder
    # and every folder above it
    seen = []
    cands = [Path.home() / ".claude" / "CLAUDE.md"]
    chain = list(reversed([root] + list(root.parents)))
    for d in chain:
        cands += [d / "CLAUDE.md", d / "CLAUDE.local.md"]
    cands.append(root / ".claude" / "CLAUDE.md")
    for p in cands:
        try:
            if p.is_file() and str(p).lower() not in seen:
                seen.append(str(p).lower())
                info["claude_md"].append({"path": str(p), "text": TextFile(p).text[:50000]})
        except (OSError, ToolError):
            pass
    data = json.dumps(info, ensure_ascii=False, indent=1) + "\n"
    sys.stdout.buffer.write(data.encode("utf-8"))
    sys.stdout.buffer.flush()


def main():
    ap = argparse.ArgumentParser(description="Piggy Desk's tools on this PC (MCP over stdio)")
    ap.add_argument("--root", default=os.getcwd(), help="the project folder")
    ap.add_argument("--mkdir", action="store_true", help="create the project folder if it doesn't exist")
    ap.add_argument("--context", action="store_true", help="print facts about the PC and the project as JSON, then exit")
    args = ap.parse_args()
    State.root = resolve(args.root, os.getcwd())
    if args.mkdir:
        State.root.mkdir(parents=True, exist_ok=True)
    State.cwd = str(State.root)
    if args.context:
        context()
        return
    serve()


if __name__ == "__main__":
    main()
