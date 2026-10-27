# Piggy Desk

平板上的 Claude 工作台（荣耀 MagicPad 13，横屏 2880×1840）。界面照 Claude Desktop 的「代码」模式做：
左边项目和会话，中间对话，右边环境、上下文、输出内容。

- Claude Code 跑在应用自带的 Linux（proot Alpine）里，只在平板上登录。
- Claude 的网络只在白名单 Wi-Fi（手机热点）上放行；应用内置出口代理，电脑上的小猪书记「平板」线路也走它。
- 不连电脑也能在平板上干活；设置里连上电脑（Tailscale + SSH）后，项目可以放在电脑上，电脑只当工作空间。
- 保留 Piggy 的无障碍小猪。

基于 [piggy-assistant](https://github.com/xjtubaizeyu-commits/piggy-assistant)（Buddy 的分支，Apache-2.0）。
包名 `app.piggy.desk`，可以和 Piggy 同时安装。界面和应用之间的约定见 [docs/desk-bridge.md](docs/desk-bridge.md)。

构建：推到 GitHub 后由 Actions 编译（`.github/workflows/build.yml`），打 `v*` 标签发布 `piggy-desk.apk`。
