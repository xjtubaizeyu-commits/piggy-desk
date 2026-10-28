# Claude Desktop（Code 模式）功能对照

这份文档对照两样东西：Claude Desktop「Code」模式有哪些功能，以及 Claude Code 2.1.3xx 的 SDK 控制协议提供了哪些能力。协议内容是直接从 `claude` 程序里读出来的。表里同时标出 Piggy Desk 做到了哪一步。

- ✅ 已做
- 🔜 下一步
- — 不打算做

## 布局

| Desktop | Piggy Desk |
| --- | --- |
| 左侧栏：会话按项目分组，可以折叠；有置顶、未读点、搜索 | ✅ 置顶分组、未读点、搜索（标题加内容） |
| 左右两边都能拖动改宽度 | ✅ 拖内侧边缘，双击还原，宽度会记住 |
| 右侧窗格分页：改动（diff）、文件、终端、计划、任务、浏览器、Artifacts | ✅ 概览、计划、改动、文件、浏览器，加上打开的文件各占一页；终端仍是原生界面 🔜 |
| 右侧窗格可以放大 | ✅ 一键放大或还原 |
| 分屏同时看两个会话 | — 平板屏幕不够 |
| 对话目录（章节） | ✅ 概览里的「目录」：每条你的消息一行，点一下跳过去 |

## 右侧能打开的东西

| 类型 | Piggy Desk |
| --- | --- |
| Markdown | ✅ 渲染或看源码 |
| 代码 | ✅ 语法高亮加行号 |
| 图片 | ✅ png、jpg、gif、webp、svg 等 |
| PDF | ✅ Android 自带的 PdfRenderer 逐页画出来，滚到哪页画哪页 |
| HTML | ✅ 静态预览（不跑脚本）；「在浏览器打开」能跑脚本，旁边的 css、js 也能加载 |
| CSV、TSV | ✅ 表格 |
| Jupyter 笔记本 | ✅ 单元格和输出图 |
| JSON | ✅ 格式化 |
| 音频、视频 | ✅ 播放器 |
| 其他（docx、xlsx…） | ✅ 用其他应用打开 |
| 电脑上的文件 | ✅ 先用 scp 取到平板缓存再显示，改过才重新取 |

## 浏览器

| Desktop | Piggy Desk |
| --- | --- |
| 内置浏览器窗格，Claude 能操作 | ✅ 原生 WebView 盖在右侧栏的空位上，有地址栏、前进、后退和刷新 |
| Claude 的浏览器工具 | ✅ MCP 服务器 `browser`：`open`、`read`（可点的元素带编号）、`click`、`type`、`scroll`、`back`、`screenshot` |
| 密码、支付交给用户 | ✅ 遇到密码框、验证码框、银行卡框，`type` 会拒绝 |
| 菜单盖在网页上 | ✅ 打开菜单或对话框时网页先藏起来，关掉再回来 |

## 记忆（分级）

Claude Code 读说明文件的顺序是从宽到窄，越具体的越优先：

1. 组织策略 `/etc/claude-code/CLAUDE.md`（只读）
2. 用户全局 `~/.claude/CLAUDE.md`
3. 项目 `./CLAUDE.md`，或者 `./.claude/CLAUDE.md`
4. 本地 `./CLAUDE.local.md`（不进 git）
5. 子文件夹里的 `CLAUDE.md`：用到那个文件夹时才读
6. 自动记忆：`~/.claude/projects/<工作文件夹>/memory/`
   - `MEMORY.md` 是索引，每条记忆在索引里占一行
   - 每条记忆一个文件，带 front matter：`name`、`description`、`type`
   - `type` 是 `user`、`feedback`、`project`、`reference` 之一
   - 还有团队记忆 `team/`，以及子代理的记忆
7. 自动整理（auto-dream）：空闲时在后台合并、清理记忆

协议里和记忆有关的部分：

- `get_memory_dialog`：返回文件行、文件夹行、开关状态，和终端里 `/memory` 看到的一样
- `system/memory_saved`：Claude 写了记忆
- `system/memory_recall`：这一轮想起了哪些记忆，`select` 是整篇读，`synthesize` 是概括成一段
- 设置项：`autoMemoryEnabled`、`autoDreamEnabled`、`autoMemoryDirectory`

| | Piggy Desk |
| --- | --- |
| 记忆页：按项目列出每一级，可以查看、编辑、新建 | ✅ 包括电脑项目在电脑上的文件 |
| 自动记忆卡片：类型、说明、修改时间；可以编辑、删除（同时删掉索引里那行） | ✅ |
| 自动记忆和自动整理两个开关 | ✅ 写进 `~/.claude/settings.json` |
| 对话里显示「记住了 / 想起了 N 条记忆」，点开就是那份文件 | ✅ |
| 概览里的「记忆」卡片：这个会话读了哪些文件 | ✅ |
| `/memory` | ✅ 直接打开记忆页 |

## 会话

| 功能 | 协议 | Piggy Desk |
| --- | --- | --- |
| 自动起标题 | `generate_session_title` | ✅ 第一轮结束后起 |
| 重命名、归档、删除、置顶 | `rename_session` | ✅ |
| 分叉 | `--resume <旧> --fork-session --session-id <新>` | ✅ |
| 导出 | `export_conversation` | ✅ 存成 Markdown，放在「下载/Piggy Desk」 |
| 下一句建议 | `--prompt-suggestions`，输出 `prompt_suggestion` | ✅ 输入框为空时显示，按 Tab 填入 |
| 顺口问一句 /btw | `side_question` | ✅ 弹窗里回答，不打断手上的活 |
| 计划 | `get_plan`、ExitPlanMode、`~/.claude/plans/*.md` | ✅ 计划页；改了会出红点 |
| 后台任务 | `task_started`、`task_progress`、`task_updated`、`task_notification`、`background_tasks_changed`、`get_task_output`、`stop_task` | ✅ 概览里列出来，能看输出、能停止 |
| @ 提到文件 | `file_suggestions` | ✅ 用平板自己的文件索引（电脑项目走 git ls-files） |
| 网络重试提示 | `system/api_retry` | ✅「网络不稳，重试中…」 |
| 完成或等确认时通知 | — | ✅ 应用在后台时发通知，点一下回到那个会话 |
| 输出风格 | `outputStyle` | ✅ 默认、讲解、学习 |
| 回退（rewind，撤回到某条消息） | `rewind_files`、`--resume-session-at` | 🔜 要开 `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING` |
| 结构化 diff | `get_workspace_diff` | 🔜 现在用 git diff 自己解析，电脑项目也能用 |
| MCP 状态和开关 | `mcp_status`、`mcp_toggle`、`mcp_reconnect` | 🔜 |
| 技能和权限规则页 | `get_skills_dialog`、`list_permission_rules`、`get_hooks_listing` | 🔜 |
| /status 页 | `get_status` | 🔜 |
| Artifacts、云端会话、Slack | — | — |
