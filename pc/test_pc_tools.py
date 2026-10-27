"""Tests for pc-tools.py: starts it as an MCP server on a temp folder and calls every tool.

    python pc\\test_pc_tools.py

Uses its own PIGGY_DESK_HOME, so background-job files don't land in the real %USERPROFILE%\\.piggy-desk.
"""

import base64
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
SERVER = HERE / "pc-tools.py"


class Client:
    def __init__(self, root, home, env_extra=None):
        env = dict(os.environ, PIGGY_DESK_HOME=str(home))
        env.update(env_extra or {})
        self.p = subprocess.Popen([sys.executable, str(SERVER), "--root", str(root)], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        self.replies = {}
        self.cv = threading.Condition()
        self.seq = 0
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.p.stdout:
            msg = json.loads(line.decode("utf-8"))
            with self.cv:
                self.replies[msg.get("id")] = msg
                self.cv.notify_all()

    def send(self, method, params=None, notify=False):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if not notify:
            self.seq += 1
            msg["id"] = self.seq
        self.p.stdin.write((json.dumps(msg, ensure_ascii=False) + "\n").encode("utf-8"))
        self.p.stdin.flush()
        return msg.get("id")

    def wait(self, mid, timeout=60):
        end = time.time() + timeout
        with self.cv:
            while mid not in self.replies:
                left = end - time.time()
                if left <= 0:
                    raise AssertionError("no reply to %s" % mid)
                self.cv.wait(left)
            return self.replies.pop(mid)

    def call(self, method, params=None, timeout=60):
        return self.wait(self.send(method, params), timeout)

    def tool(self, name, timeout=60, **args):
        r = self.call("tools/call", {"name": name, "arguments": args}, timeout)
        assert "result" in r, r
        res = r["result"]
        text = "\n".join(c.get("text", "") for c in res["content"] if c["type"] == "text")
        return res, text

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=10)
        except Exception:
            self.p.kill()


passed = []


def check(name, cond, detail=""):
    if not cond:
        raise AssertionError("%s failed: %s" % (name, detail))
    passed.append(name)
    print("ok  " + name)


def tiny_png():
    raw = b"\x00\xff\x00\x00"  # one red pixel, filter byte 0
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def main():
    tmp = Path(tempfile.mkdtemp(prefix="pctools-"))
    root = tmp / "项目 root"
    root.mkdir()
    home = tmp / "home"
    c = Client(root, home)
    try:
        run(c, root, home)
    finally:
        c.close()
    # grep again without ripgrep: the Python fallback
    c2 = Client(root, home, {"PIGGY_RG": "none"})
    try:
        c2.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
        res, text = c2.tool("grep", pattern="世界", output_mode="content")
        check("grep fallback content", "世界" in text and ":2:" in text, text)
        res, text = c2.tool("grep", pattern="ALPHA", **{"-i": True})
        check("grep fallback -i files", "notes.md" in text and "a.py" not in text, text)
        res, text = c2.tool("grep", pattern="a", glob="*.{py,md}", output_mode="count")
        check("grep fallback glob braces", "notes.md" in text and "测试 文件.txt" not in text, text)
    finally:
        c2.close()
    # --context
    (root / "CLAUDE.md").write_text("# 项目说明\n用 torchpy\n", encoding="utf-8")
    out = subprocess.run([sys.executable, str(SERVER), "--context", "--root", str(root)], capture_output=True, timeout=60)
    ctx = json.loads(out.stdout.decode("utf-8"))
    check("context fields", all(k in ctx for k in ("hostname", "windows", "gpu", "tools", "claude_md", "git_branch", "root")), ctx.keys())
    check("context project CLAUDE.md", any("项目说明" in m["text"] for m in ctx["claude_md"]), [m["path"] for m in ctx["claude_md"]])
    newdir = tmp / "new project"
    subprocess.run([sys.executable, str(SERVER), "--context", "--mkdir", "--root", str(newdir)], capture_output=True, timeout=60)
    check("--mkdir creates the project folder", newdir.is_dir())
    shutil.rmtree(tmp, ignore_errors=True)
    print("\nall %d checks passed" % len(passed))


def run(c, root, home):
    r = c.call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
    check("initialize echoes version", r["result"]["protocolVersion"] == "2025-06-18", r)
    check("serverInfo", r["result"]["serverInfo"]["name"] == "pc", r)
    c.send("notifications/initialized", notify=True)
    r = c.call("initialize", {"protocolVersion": "1999-01-01"})
    check("initialize unknown version", r["result"]["protocolVersion"] == "2025-06-18", r)
    r = c.call("tools/list")
    names = {t["name"] for t in r["result"]["tools"]}
    want = {"bash", "powershell", "read", "write", "edit", "glob", "grep", "ls", "bg_start", "bg_output", "bg_kill", "bg_list"}
    check("tools/list", names == want, names)
    check("schemas", all(t["inputSchema"]["type"] == "object" and t["description"] for t in r["result"]["tools"]))
    r = c.call("tools/call", {"name": "nope", "arguments": {}})
    check("unknown tool is an error", "error" in r, r)

    # write / read with a Chinese name and content
    f = root / "测试 文件.txt"
    res, text = c.tool("write", file_path=str(f), content="你好\n世界\n第三行\n")
    check("write new", f.read_bytes() == "你好\n世界\n第三行\n".encode("utf-8") and not res.get("isError"), text)
    res, text = c.tool("read", file_path="测试 文件.txt")
    check("read relative + numbered", "     1\t你好" in text and "     2\t世界" in text, text)
    res, text = c.tool("read", file_path=str(f), offset=2, limit=1)
    check("read offset/limit", "     2\t世界" in text and "你好" not in text, text)
    res, text = c.tool("read", file_path=str(root / "missing.txt"))
    check("read missing is error", res.get("isError") and "不存在" in text, text)

    # edit on a CRLF file keeps CRLF
    crlf = root / "win.txt"
    crlf.write_bytes(b"alpha\r\nbeta\r\ngamma\r\n")
    res, text = c.tool("edit", file_path=str(crlf), old_string="beta\ngamma", new_string="BETA\nGAMMA\ndelta")
    check("edit CRLF", crlf.read_bytes() == b"alpha\r\nBETA\r\nGAMMA\r\ndelta\r\n", crlf.read_bytes())
    # BOM kept
    bom = root / "bom.ps1"
    bom.write_bytes(b"\xef\xbb\xbf" + "Write-Host '旧'\n".encode("utf-8"))
    c.tool("edit", file_path=str(bom), old_string="旧", new_string="新")
    check("edit keeps BOM", bom.read_bytes() == b"\xef\xbb\xbf" + "Write-Host '新'\n".encode("utf-8"), bom.read_bytes())
    # GBK file stays GBK
    gbk = root / "gbk.txt"
    gbk.write_bytes("中文内容\n".encode("gbk"))
    c.tool("edit", file_path=str(gbk), old_string="内容", new_string="文本")
    check("edit keeps GBK", gbk.read_bytes() == "中文文本\n".encode("gbk"), gbk.read_bytes())
    # uniqueness rules
    dup = root / "dup.txt"
    dup.write_text("x = 1\nx = 1\n", encoding="utf-8")
    res, text = c.tool("edit", file_path=str(dup), old_string="x = 1", new_string="x = 2")
    check("edit not unique is error", res.get("isError") and "2 次" in text, text)
    res, text = c.tool("edit", file_path=str(dup), old_string="x = 1", new_string="x = 2", replace_all=True)
    check("edit replace_all", dup.read_text(encoding="utf-8") == "x = 2\nx = 2\n", text)
    res, text = c.tool("edit", file_path=str(dup), old_string="nothing here", new_string="y")
    check("edit not found is error", res.get("isError") and "找不到" in text, text)
    res, text = c.tool("write", file_path=str(root / "new.ps1"), content="Write-Host '中文'\n")
    check("new .ps1 with Chinese gets a BOM", (root / "new.ps1").read_bytes().startswith(b"\xef\xbb\xbf"), text)
    # writing over a CRLF file keeps CRLF
    c.tool("write", file_path=str(crlf), content="one\ntwo\n")
    check("write keeps CRLF", crlf.read_bytes() == b"one\r\ntwo\r\n", crlf.read_bytes())

    # bash: output, Chinese, persistent cwd, exit codes
    res, text = c.tool("bash", command="echo 中文输出; pwd -W")
    check("bash Chinese + starts in root", "中文输出" in text and root.name in text, text)
    (root / "sub").mkdir()
    c.tool("bash", command="cd sub")
    res, text = c.tool("bash", command="pwd -W")
    check("bash cwd persists", text.strip().endswith("/sub"), text)
    res, text = c.tool("powershell", command="Write-Output \"你好 PowerShell\"; (Get-Location).Path")
    check("powershell Chinese + shared cwd", "你好 PowerShell" in text and text.strip().endswith("sub"), text)
    c.tool("bash", command="cd ..")
    res, text = c.tool("bash", command="exit 3")
    check("bash exit code", res.get("isError") and "退出码 3" in text, text)
    res, text = c.tool("bash", command="python -c \"print('py 输出')\"")
    check("bash runs python with UTF-8 output", "py 输出" in text, text)

    # timeout kills the command
    t0 = time.time()
    res, text = c.tool("bash", command="sleep 30; echo never", timeout_ms=2000, timeout=40)
    check("timeout", res.get("isError") and "超时" in text and "never" not in text and time.time() - t0 < 20, text)

    # ping answers while a command runs
    slow = c.send("tools/call", {"name": "bash", "arguments": {"command": "sleep 3; echo slow-done"}})
    time.sleep(0.5)
    t0 = time.time()
    c.call("ping", timeout=10)
    check("ping during a command", time.time() - t0 < 1.5)
    r = c.wait(slow, 30)
    check("slow command finishes", "slow-done" in r["result"]["content"][0]["text"], r)

    # cancellation
    mid = c.send("tools/call", {"name": "bash", "arguments": {"command": "sleep 30"}})
    time.sleep(1.5)
    t0 = time.time()
    c.send("notifications/cancelled", {"requestId": mid, "reason": "test"}, notify=True)
    r = c.wait(mid, 20)
    check("cancel stops the command", time.time() - t0 < 10 and "已取消" in r["result"]["content"][0]["text"], r)

    # glob, grep, ls
    (root / "a.py").write_text("print('a')\n", encoding="utf-8")
    (root / "sub" / "b.py").write_text("print('b')\n", encoding="utf-8")
    (root / "notes.md").write_text("alpha\n", encoding="utf-8")
    res, text = c.tool("glob", pattern="**/*.py")
    check("glob **", "a.py" in text and "b.py" in text and "notes.md" not in text, text)
    res, text = c.tool("glob", pattern=str(root).replace("\\", "/") + "/sub/*.py")
    check("glob absolute pattern", "b.py" in text and "a.py" not in text, text)
    res, text = c.tool("grep", pattern="世界", output_mode="content")
    check("grep content", "世界" in text and ":2:" in text, text)
    res, text = c.tool("grep", pattern="print", output_mode="count")
    check("grep count", "a.py:1" in text and "b.py:1" in text, text)
    res, text = c.tool("grep", pattern="PRINT", **{"-i": True}, glob="*.py")
    check("grep -i + glob", "a.py" in text and "notes.md" not in text, text)
    res, text = c.tool("grep", pattern="zzz-not-there")
    check("grep no match", "没有匹配" in text, text)
    res, text = c.tool("ls")
    check("ls", "sub/" in text and "a.py" in text, text)

    # images
    png = tiny_png()
    (root / "dot.png").write_bytes(png)
    res, text = c.tool("read", file_path="dot.png")
    img = [x for x in res["content"] if x["type"] == "image"]
    check("read png as image", img and img[0]["mimeType"] == "image/png" and base64.b64decode(img[0]["data"]) == png, res)

    # background jobs
    res, text = c.tool("bg_start", command="for i in 1 2 3; do echo line$i; sleep 1; done", description="count")
    m = re.search(r"j\d+", text)
    check("bg_start", m and (home / "jobs" / (m.group() + ".log")).exists(), text)
    jid = m.group()
    time.sleep(1.6)
    res, text = c.tool("bg_output", id=jid)
    check("bg_output new lines", "line1" in text and "运行中" in text, text)
    time.sleep(3)
    res, text = c.tool("bg_output", id=jid)
    check("bg_output only new + exit code", "line1" not in text and "line3" in text and "退出码 0" in text, text)
    res, text = c.tool("bg_output", id=jid, tail_lines=2)
    check("bg_output tail", "line2" in text and "line3" in text and "line1" not in text, text)
    res, text = c.tool("bg_start", command="Start-Sleep -Seconds 60", shell="powershell")
    jid2 = re.search(r"j\d+", text).group()
    time.sleep(1)
    res, text = c.tool("bg_kill", id=jid2)
    check("bg_kill", "已停止" in text, text)
    res, text = c.tool("bg_list")
    check("bg_list", jid in text and jid2 in text and "bg_kill" in text, text)
    res, text = c.tool("bg_output", id="j999")
    check("bg unknown id is error", res.get("isError"), text)


if __name__ == "__main__":
    main()
