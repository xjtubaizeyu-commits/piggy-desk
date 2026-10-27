# 电脑端工具（pc-tools.py）

项目放在电脑上时，平板上的 Claude Code 通过这个程序在电脑上读写文件、跑命令。它是一个 MCP 服务器，走 SSH 的标准输入输出。电脑本身不连 Anthropic，Claude 还在平板上运行。

- 只用 Python 标准库，电脑上自带的 `python`（3.11）就能跑，不用装任何东西。
- 平板每次连电脑时，会把它推送到 `%USERPROFILE%\.piggy-desk\pc-tools.py`，然后这样启动：

  ```
  ssh piggy-pc python .piggy-desk\pc-tools.py --root "C:\Users\bzy15\Desktop\项目"
  ```

## 工具

Claude 那边看到的名字是 `mcp__pc__<名字>`，用法和 Claude Code 自带的工具一样。

| 工具 | 作用 |
| --- | --- |
| `bash` | 在 Git Bash 里跑命令。工作目录在多次调用之间保持。默认超时 2 分钟，最长 10 分钟，超时会结束整棵进程树 |
| `powershell` | 在 Windows PowerShell 5.1 里跑命令，和 bash 共用工作目录，输出是 UTF-8 |
| `read` | 读文件，带行号，默认 2000 行。图片会作为图片返回 |
| `write` | 写整个文件。覆盖已有文件时保留它的编码、BOM 和换行符；新建的 .ps1 如果含中文会加 BOM |
| `edit` | 精确替换文本，规则同 Claude Code 的 Edit。保留 CRLF、BOM 和 GBK 编码 |
| `glob` | 按模式找文件，最新的在前 |
| `grep` | 正则搜索。有 ripgrep 就用 ripgrep（会自动找 VS Code 自带的那个），没有就用 Python 自己搜 |
| `ls` | 列目录 |
| `bg_start` / `bg_output` / `bg_kill` / `bg_list` | 后台任务，适合训练、编译、开服务这类长时间运行的事。断线后照样跑，日志和退出码在 `%USERPROFILE%\.piggy-desk\jobs\` |

`python pc-tools.py --context --root <项目>` 会打印一段 JSON 后退出，平板用它来拼系统提示。内容包括：
- 主机名和 Windows 版本
- 显卡
- 有没有 git、torchpy、node、uv
- 项目的 git 分支
- 和 Claude Code 在电脑上运行时会读到的同一批 CLAUDE.md：`~/.claude/CLAUDE.md`，加上项目文件夹及它以上每一层的 CLAUDE.md

加上 `--mkdir` 时，如果项目文件夹不存在会先建好。

## 测试

```
python pc\test_pc_tools.py
```

测试会在临时文件夹里把每个工具都跑一遍，共 48 项。覆盖到的情况包括：
- 中文文件名和中文内容
- CRLF、BOM、GBK 编码的文件
- 命令超时、取消
- 命令运行中 `ping` 照样有回应
- 后台任务
- 读 PNG 图片
- 没有 ripgrep 时的 Python 搜索
