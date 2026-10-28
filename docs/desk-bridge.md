# Desk bridge

The tablet workspace is a web page (`app/src/main/assets/desk/`) in a WebView (`DeskActivity`). The page and the app talk
only through this bridge. Everything is JSON.

- **Page → app:** `PiggyDesk.post(JSON.stringify(msg))`. A message that carries an `id` gets exactly one `reply`.
- **App → page:** `desk.recv(msg)` (a global the page defines), with `desk.back()` returning `true` if it closed something.
- **Without the app** (a desktop browser, `?mock=1`), `mock.js` stands in for `PiggyDesk` with made-up data, so the page
  can be built and checked on the PC.

Times are epoch milliseconds. Ids are strings. Unknown fields are ignored on both sides; new fields may appear.

## App → page

### `state`: everything, once after `ready` (and again whenever the app wants a full refresh)

```json
{"type": "state",
 "app": {"version": "0.1.0", "id": "app.piggy.desk"},
 "setup": {"linux": true, "claude": "ready", "version": "2.3.1", "detail": ""},
 "account": {"signedIn": true, "email": "x@y.z", "name": "PIG", "plan": "Pro"},
 "net": {"ssid": "MIX 4", "allowed": true, "allow": ["MIX 4"], "reason": ""},
 "egress": {"on": true, "port": 8899, "code": "aec1-…", "meshIp": "100.123.6.79", "lastPeer": 1761560000000},
 "pc": {"on": true, "host": "100.84.31.4", "user": "bzy15", "status": "ok", "detail": "", "publicKey": "ssh-ed25519 …"},
 "prefs": {"model": "opus", "effort": "xhigh", "mode": "auto", "theme": "dark", "fontSize": 15},
 "models": [{"value": "opus", "label": "Opus 5.5", "description": "…"}],
 "efforts": ["low", "medium", "high", "xhigh", "max"],
 "modes": ["default", "acceptEdits", "plan", "auto", "bypassPermissions"],
 "commands": [{"name": "compact", "description": "…", "argumentHint": ""}],
 "usage": {"fiveHour": {"pct": 42, "resetsAt": 1761570000000, "status": "allowed"},
           "weekly": {"pct": 12, "resetsAt": 1761900000000, "status": "allowed"}},
 "projects": [{"id": "p1", "name": "piggy-desk", "env": "tablet", "path": "/root/projects/piggy-desk", "collapsed": false}],
 "sessions": [{"id": "s1", "projectId": "p1", "title": "…", "updatedAt": 1761560000000, "archived": false,
               "running": false, "unread": false, "model": "opus", "effort": "xhigh", "mode": "auto"}]}
```

- `setup.claude`: `missing` | `installing` | `ready` | `error`.
- `net.allowed`: Claude may connect now. It is true only on an allowed Wi-Fi. `reason` says why not.
- `pc.status`: `off` | `connecting` | `ok` | `error`.
- `usage.*.pct` may be `null` (Claude Code doesn't always say how much is used, only whether it is allowed).

### Partial updates
Each one replaces that part of `state`: `{"type": "setup" | "account" | "net" | "egress" | "pc" | "prefs" | "usage" | "projects" | "sessions" | "commands" | "models", "value": …}`.

### Session events (all carry `sessionId`)

| type | fields | meaning |
| --- | --- | --- |
| `transcript` | `items` | the whole conversation, when a session is opened |
| `item` | `item` | add an item, or replace the one with the same `item.id` |
| `delta` | `itemId`, `text` | append streamed text to an `assistant` or `thinking` item |
| `status` | `running`, `phase` (`starting` \| `connecting` \| `thinking` \| `tool` \| `waiting` \| `compacting` \| `idle`), `since` | the spinner line |
| `context` | `used`, `window`, `breakdown` `[{label, tokens}]` | the 上下文 panel (`breakdown` may be empty) |
| `outputs` | `files` `[{path, action: "write"\|"edit", at}]` | the 输出内容 panel |
| `todos` | `todos` `[{content, status, activeForm}]` | the to-do panel |
| `env` | `env` (`tablet`\|`pc`), `cwd`, `branch`, `remote` | the 环境 panel |
| `permission` | `requestId`, `tool`, `input`, `kind` (`tool` \| `plan` \| `question`), `suggestions`, `reason`, `blockedPath` | a card waiting for an answer |
| `permissionDone` | `requestId` | that card was answered (here or elsewhere) |
| `plan` | `text`, `path`, `at`, `open` | the 计划 tab (`open`: switch to it, Claude asks to start on it) |
| `tasks` | `tasks` `[{id, kind: "shell"\|"agent", description, status, at}]` | background tasks, in 概览 |
| `suggestion` | `text` | Claude Code's guess at the next prompt: the empty composer shows it, Tab takes it |
| `memoryChanged` | | auto-memory was written: the 记忆 card reads it again |

Items (`item.kind`):

- `user`: `text`, `attachments` `[{id, name, kind: "image"|"file", thumb}]` (`thumb` is a data: URL or empty).
- `assistant`: `text` (Markdown).
- `thinking`: `text`.
- `tool`: `name`, `input` (object), `status` (`running` | `done` | `error` | `denied`), `output` (text), `images`
  (data: URLs), `parentId` (a subagent's tool calls point at the `Task` call). The page picks the look from `name`:
  `Bash`, `Read`, `Write`, `Edit`, `MultiEdit`, `Glob`, `Grep`, `WebFetch`, `WebSearch`, `TodoWrite`, `Task`, `ExitPlanMode`,
  `AskUserQuestion`, `mcp__pc__*` (the PC's tools, same shapes as the built-in ones), anything else generic.
- `system`: `text`, `level` (`info` | `warn` | `error`).
- `compact`: `text`, a "conversation compacted" divider.
- `memory`: `paths`, `recalled` (true: recalled for this turn, false: saved), `verb` (记住了 / 更新了 / 删掉了 / 想起了).

### Other

- `dictation`: `text`, `final`, live speech-to-text for the composer.
- `toast`: `text`.
- `browser`: `url`, `title`, `canBack`, `canForward`, `loading`, `progress`, `shown` (Claude is using it: show the tab).
- `open`: `sessionId`, open this session (a notification was tapped).

## Page → app

`ready` first. Requests marked ⇄ carry an `id` and get `{"type": "reply", "id", "ok", "data" | "error"}`.

| type | fields | |
| --- | --- | --- |
| `ready` | | the page loaded; the app answers with `state` |
| `newSession` ⇄ | `projectId` | → `{sessionId}` |
| `openSession` | `sessionId` | the app answers with `transcript` and the session's panels |
| `send` | `sessionId`, `text`, `attachments` `[id]` | a message (a leading `/` is a slash command) |
| `interrupt` | `sessionId` | stop the current reply |
| `answer` | `sessionId`, `requestId`, `decision` (`allow` \| `allowAlways` \| `deny`), `message`, `updatedInput` | a permission, plan or question card |
| `setMode` | `sessionId` (or none: the default), `mode` | |
| `setModel` | `sessionId` (or none: the default), `model`, `effort` | |
| `renameSession` / `archiveSession` / `deleteSession` | `sessionId`, `title` | |
| `newProject` ⇄ | `name`, `env`, `path` | → `{projectId}`; `path` empty = a new folder on the tablet |
| `editProject` / `removeProject` | `projectId`, … | `removeProject` never deletes files |
| `pickAttachment` ⇄ | `kind` (`image` \| `file` \| `camera`) | → `{attachments: [{id, name, kind, thumb}]}` |
| `dictate` | `on` | start / stop speech input |
| `search` ⇄ | `query` | → `{hits: [{sessionId, title, snippet}]}` |
| `listFiles` ⇄ | `sessionId`, `path` | → `{entries: [{name, dir, size}]}` |
| `readFile` ⇄ | `sessionId`, `path` | → `{text}` or `{image}` |
| `gitDiff` ⇄ | `sessionId` | → `{diff}` |
| `remoteControl` | `sessionId`, `on` | hand the session to Claude Code's Remote Control, or take it back |
| `refreshUsage` / `refreshContext` | `sessionId` | |
| `signIn` / `signOut` / `installClaude` / `installClaudeFile` / `updateClaude` | | `installClaudeFile` asks for claude.zst or the program |
| `setPref` | `key`, `value` | `theme`, `fontSize`, `model`, `effort`, `mode`, `allowSsid`, … |
| `egressSet` | `on`, `port`, `code` | the built-in exit for the PC |
| `pcSet` | `on`, `host`, `user` | |
| `pcTest` ⇄ | | → `{ok, detail}` |
| `openNative` | `screen` (`terminal` \| `accessibility` \| `rules` \| `schedules` \| `bubble`) | Piggy's own screens |
| `openUrl` | `url` | opens in the browser |
| `copy` | `text` | to the clipboard |
| `toast` | `text` | |
| `customGet` ⇄ | | → `{claudeMd, skills, agents, commands, mcpServers}` (each list `[{name, description, path, type, status}]`) |
| `customSave` ⇄ | `claudeMd` | writes the tablet's `~/.claude/CLAUDE.md` |
| `readFile` ⇄ | `sessionId`, `path`, `where` (`""` the project's place \| `tablet` \| `pc`) | → `{kind: text\|image\|pdf\|html\|media\|binary\|big, text, url, pages, sizes, browseUrl, size, truncated, shareable}`; `url` is `https://appassets.androidplatform.net/fs/<token>/<name>`, PDF pages `…/pdf/<token>/<n>.png?w=` |
| `fileIndex` ⇄ | `sessionId` or `projectId` | → `{files: [relative path]}` for @ and 文件 |
| `gitDiff` ⇄ | `sessionId` | → `{branch, diff, untracked}` |
| `openFile` | `sessionId`, `path`, `where` | opens the file in another app |
| `planRefresh` | `sessionId` | asks Claude Code for the plan (`get_plan`) |
| `stopTask` / `taskOutput` ⇄ | `sessionId`, `taskId` | `stop_task`; `get_task_output` → `{output, truncated}` |
| `sideQuestion` ⇄ | `sessionId`, `question` | /btw (`side_question`) → `{answer}` |
| `pinSession` | `sessionId`, `pinned` | |
| `forkSession` ⇄ | `sessionId` | → `{sessionId}`; the copy's first start is `--resume <old> --fork-session --session-id <new>` |
| `exportSession` ⇄ | `sessionId` | → `{where}`, Markdown in Downloads/Piggy Desk |
| `memoryGet` ⇄ | `projectId` | → `{levels: [{kind, label, desc, where, path, exists, readOnly}], auto: {enabled, dream, dir, index, memories: [{name, title, path, type, description, modified, size}]}}` |
| `memoryRead` / `memorySave` ⇄ | `projectId`, `where`, `path`, `text` | instruction files and auto-memory notes only |
| `memoryDelete` ⇄ | `projectId`, `path` | an auto-memory note, and its line in MEMORY.md |
| `memorySet` ⇄ | `projectId`, `autoMemoryEnabled`, `autoDreamEnabled` | `~/.claude/settings.json` |
| `browser` | `op` (`go` \| `back` \| `forward` \| `reload` \| `stop` \| `clear`), `url` | the side panel's browser |
| `browserSlot` | `visible`, `x`, `y`, `w`, `h` | where the native browser goes, in CSS pixels; hidden while a menu or dialog is open |

## How the app answers (Desk.java, Engine.java)

- Each session runs one `claude -p --input-format stream-json --output-format stream-json --verbose
  --include-partial-messages --permission-prompt-tool stdio` inside the app's Linux, driven with Claude Code's SDK
  control protocol: `initialize` (models, commands, account), `interrupt`, `set_permission_mode`, `set_model`,
  `can_use_tool` (→ `permission` cards), `get_context_usage` (the 上下文 panel after each turn), `get_usage` and
  `rate_limit_event` (the 额度 ring), `remote_control` (the toggle).
- The page's mode `default` is Claude Code's `manual` on the command line (`default` again in `set_permission_mode`).
- Effort is fixed when Claude Code starts (`--effort`): changing it restarts the session at the next message
  (`--resume` keeps the conversation). Idle sessions stop after 15 minutes; at most 3 run at once.
- A project on the PC runs Claude Code in an empty folder on the tablet with the PC's `pc` MCP server
  (`ssh … python .piggy-desk\pc-tools.py --root <folder>`), the local file and shell tools switched off, and the
  PC's CLAUDE.md files in the system prompt.

## The side panel's browser and Claude

- `DeskBrowser` is a native WebView over the slot the page leaves in its 浏览器 tab. It has no bridge to the app.
- Claude drives it through an MCP server on `127.0.0.1:8776` (a token per app run): `open`, `read` (text with numbered
  items), `click`, `type` (refuses password, one-time-code and payment fields), `scroll`, `back`, `screenshot`. Each call
  goes through Claude Code's permission mode like any tool. Settings → 浏览器 turns it off.
- Project HTML opens there at `https://local.piggy-desk/<token>/<file>`, with the files beside it reachable.

## Memory

Claude Code reads, broad to specific: `/etc/claude-code/CLAUDE.md` (policy), `~/.claude/CLAUDE.md`, the project's
`CLAUDE.md` and `.claude/CLAUDE.md`, and `CLAUDE.local.md`. A PC project also gets the PC's own files in its system
prompt. Auto-memory lives in `~/.claude/projects/<working folder with every non-alphanumeric character a dash>/memory/`:
`MEMORY.md` (the index) and one note per memory with front matter `name`, `description` and `type` (`user`, `feedback`,
`project`, `reference`). Claude Code reports `system/memory_saved` and `system/memory_recall`, shown as `memory` items.
