# Claude 工作台 · 手机远程控制（独立包）

这个仓库只放**手机页 / 远程访问 / Android APK**相关的改动，和主客户端更新包隔离。
朋友用不到手机远程控制，所以主客户端的「一键更新包」里不包含这里的任何文件或代码。

需要的人可以单独下载这个仓库，运行一次安装器，把远程功能叠加到自己的
Claude 工作台客户端上；主客户端以后升级不会自动带这套功能，也不会把它发给朋友。

## 目录

```
overlay/claude-gui/        手机页、图标、远程开关（复制到客户端目录）
server/                    server-1.1.23-remote.js（适配 1.1.23 的远程版服务端）
patch/                     server-1.1.23-remote.patch（干净版 → 远程版，供维护者参考）
install/                   一键安装器
android/                   Android 壳源码（通用版，不含任何个人令牌）
apk/                       通用版 APK、Tailscale APK、安装说明
```

## 安装（其他用户）

前提：已经装好 Claude 工作台客户端 **1.1.23**，并且客户端能正常启动。

1. 把整个仓库下载/解压到任意目录（不要只拿 `server.js`）。
2. 双击 `install\一键安装手机远程控制.cmd`。
   - 安装器会自动找 `E:\AI_Claude\claude-gui`、用户目录下的 `AI_Claude\claude-gui` 等常见位置；
   - 找不到时会提示输入客户端目录；
   - 安装前会备份 `server.js` 和会被覆盖的手机页到 `data\backups\remote-install-*`；
   - 如果客户端版本不是 1.1.23，会拒绝安装（避免把不匹配的服务端换上去）。
3. 从托盘退出工作台，再双击客户端的 `启动界面.cmd` 重新打开。
4. 双击客户端的 `远程访问.cmd`，窗口会打印手机地址（带 token）。
5. 手机安装 `apk\Claude工作台-通用版-1.0.10.apk`；第一次打开把第 4 步的地址粘贴进去。
   也可以不用 APK：手机 Chrome 打开第 4 步的 HTTPS 地址，菜单里选「安装应用」。

关闭远程访问：双击客户端的 `关闭远程访问.cmd`。

## APK 说明

- 仓库里的 APK 是**通用版**，不带任何人的 Tailscale 主机名或 token。
  第一次打开会要求粘贴电脑 `远程访问.cmd` 打印的地址（包含 token），保存在本机。
- 连不上时，点错误页的「重新配置」可以重新粘贴地址。
- 连接失败时同样要检查：手机 Tailscale 是否登录、是否和别的 VPN 冲突、
  电脑是否开机联网、是否连回家里的 Wi-Fi。
- `apk\Tailscale-1.104.5.apk` 是 Tailscale 安装包；出门前电脑和手机都要登录同一个账号。
- 通用版和原来的个人版使用同一个包名/签名时可以直接覆盖安装；如果签名不同，
  需要先卸载旧版。卸载不会删除电脑上的对话记录。

## 和主客户端更新包的关系（重要）

- **主客户端核心更新**：只改 `E:\AI_Claude\claude-update-src`（干净更新源），
  用 `E:\AI_Claude\deploy\update\build-client-update-<版本>.ps1` 打包。
  更新包里不能出现 `public/m.*`、`tools/remote-control.js`、`/api/remote` 等远程内容。
- **手机/远程功能**：只改这个仓库。主客户端更新包不包含这里的内容。
- 主客户端更新包的打包脚本 `pack-update.ps1` 会检查 `server.js`；
  如果误从已装远程功能的运行目录打包，会自动用
  `deploy/update/remote-changes.patch` 把远程改动反向剥离，剥离失败就中止。
- 客户端核心升级后：远程版 `server.js` 是按版本适配的，旧版不能直接套在新版客户端上。
  要么等待这个仓库更新 `server/server-<新版本>-remote.js`，要么按下面的步骤重新生成。

## 客户端升级后怎么重新适配

1. 从主客户端更新源 `claude-update-src` 取新版本的干净 `server.js`。
2. 把远程改动重新应用到新 `server.js` 上，得到新的
   `server/server-<新版本>-remote.js`。
3. 生成补丁（在仓库根目录执行，Git Bash）：

   ```bash
   diff -u --label a/claude-gui/server.js --label b/claude-gui/server.js \
     <干净server.js> <新远程版server.js> > patch/server-<新版本>-remote.patch
   ```

4. 改 `install/install-remote.ps1` 里的 `$expectedVersion` 和 `$serverFile`。
5. 如果远程改动和客户端新代码冲突，以客户端新代码为准重新实现；
   不要为了保留远程功能而改主客户端更新包。

更推荐的做法：给这个仓库建 `remote` 分支，主客户端干净版建 `client` 分支；
客户端升级时把 `client` 合进 `remote`，冲突在 `remote` 里解决。
主客户端更新包始终只从 `client` 打包。

## 安全

- `data/gui-token.json` 是访问令牌，等于手机端的登录密码；不要发给别人，
  也不要提交到这个仓库。
- 局域网 HTTP 下 token 不加密；只在自己家的 Wi-Fi 或 Tailscale 下开启。
- Tailscale HTTPS 地址只有同一账号下的设备能访问；仍然不要公开 token。
- 这个仓库不包含任何人的 `gui-token.json`、个人 Tailscale 主机名或已配置好的 APK。
