# Claude 工作台 · 手机远程控制（独立包）

这个仓库只放**手机页 / 远程访问 / Android APK**相关的改动，和主客户端更新包隔离。
朋友用不到手机远程控制，所以主客户端的「一键更新包」里不包含这里的任何文件或代码。

需要的人可以单独下载这个仓库，运行一次安装器，把远程功能叠加到自己的
Claude 工作台客户端上；主客户端以后升级不会自动带这套功能，也不会把它发给朋友。

## 下载

- GitHub 仓库：<https://github.com/enneliang-stack/claude-workbench-remote>
- 国内加速整包（推荐发给国内用户）：
  <https://ghfast.top/https://github.com/enneliang-stack/claude-workbench-remote/archive/refs/heads/main.zip>
- 通用版 APK 直链（国内加速）：
  <https://ghfast.top/https://raw.githubusercontent.com/enneliang-stack/claude-workbench-remote/main/apk/Claude%E5%B7%A5%E4%BD%9C%E5%8F%B0-%E9%80%9A%E7%94%A8%E7%89%88-1.0.11.apk>
- **双模式 APK（2.4.17 完整内置环境）**：
  `apk/Claude工作台-双模式-2.4.17-内置环境.apk`（约 194 MB）
  - 2.4.17：隐私修正，移除安装包里示例用的真实局域网/Tailscale IP；
    确认不含 API Key、Tailscale auth key、实际 token 或对话记录。
  - 2.4.16：生成文件支持直接打开和 App 内「位置」；过程收起单浮钮；
    插话/停止可拖动吸附；任务完成提示常驻；引用菜单精简。
  - 完整包仍内置 Alpine、Node/npm、Claude Code，新用户首次安装无需
    下载环境；已装环境的用户可用 2.4.17 精简包（约 8 MB）。
  - 检查更新支持 full/slim 双通道（`apk/update.json`）。
  - 完整包超过 GitHub 普通文件 100 MB 限制，发布用 Releases，
    见 `apk/更新发布说明.txt`。
- **双模式 APK（2.4.5）**：`apk/Claude工作台-双模式-2.4.5.apk`
  - 2.4.5：隐私加固——密码框内容不再返回给模型，卡号/身份证长数字打码，
    验证码界面隐藏数字，密码/支付/转账等界面拒绝截图并交还用户。
  - 2.4.4：运行中顶部细状态条改为不可点击（触摸全部穿透，AI 可以正常点
    它后面的输入框/按钮）；停止/插话/继续收进屏幕右侧的小按钮。
    「任务已完成」面板和输入框仍可点击。
  - 2.4.3：无障碍不用每次重开。用一次 ADB 授权
    `pc/授权无障碍自动保持.cmd` 后，系统收回无障碍时 App 会在启动和开机时
    自动写回；未授权仍和以前一样在系统设置里开启。
  - 2.4.2：完成提示新增「发消息」和绿色 ✓：点发消息在当前页面弹输入框，
    发完直接用 `/api/chat` 给该对话续一轮；点 ✓ 收起提示；点提示本身
    仍打开 App 对应对话。
  - 2.4.1：「任务已完成」悬浮窗常驻，直到在 App 手机端打开对应对话才消失；
    完成后只显示状态，不再显示插话/停止；点击悬浮窗直接打开 App 当前对话；
    悬浮窗插话直接叠在当前操作的页面（需“显示在其他应用上层”权限），
    不再跳回 App 手机端；修复任务结束后仍被判为运行中（runner.busy）。
  - 2.4.0：修复任务结束后悬浮窗仍显示进行中（改为 runner.busy 判定）；
    切回 App 手机页/电脑端页自动隐藏并清除完成提示；对话删除从常驻 ×
    改为长按确认删除。
  - 2.3.9：内置 Tailscale 增加 `TS_PREFER_DERP=2`（SFO），IPv4-only Wi-Fi 下
    中继从 hkg 改走 sfo，避开电脑到香港的高延迟；直连尝试不变。
  - 2.3.8：清理命令扩大为匹配任何 `node …server.js`；启动服务后必须页面返回
    `workbench-phone` 标记才采用该端口，旧 Node 的登录页不会被当成手机端，
    App 会自动使用新 server 实际监听的端口；ASSET_VERSION 22。
  - 2.3.7：修复清理旧 Node 时 pkill 误杀执行它的 proot 包装进程；改为只匹配
    Node 自身命令行，旧进程占着 4712 的问题才能真正清掉；ASSET_VERSION 21。
  - 2.3.6：启动手机端前 `pkill` 旧 `node server.js`，修复旧进程占着 4712
    导致新 server 换端口、App 仍连旧登录页的问题；ASSET_VERSION 20。
  - 2.3.5：修复手机环境里的 server.js 没刷新：copyAssets 改为对比 APK 内
    server.js/m.html/phone-mcp.js，文件不一致就强制重新部署；server 启动日志会
    打印版本标识，方便确认运行的是新文件。
  - 2.3.4：修复手机端启动后返回「工作台 · 远程访问」登录页：手机端服务只监听
    127.0.0.1，IS_PHONE 直接视为 loopback，静态页/API 不再被登录页挡住；
    电脑端/手机端按系统返回直接回主页，不再退回另一个模式。
  - 2.3.3：修复手机端页面校验在主线程发 HTTP 导致的
    `NetworkOnMainThreadException`；校验/探测移到后台线程，手机端可正常进入。
  - 2.3.2：修复手机端启动后「手机端页面校验失败」：手机端页面/校验带本机 token，
    ASSET_VERSION 17 强制刷新手机环境 public，校验失败日志会打印状态码和页面开头。
  - 2.3.1：修复 App 内下载生成文件时「不允许的下载地址」（放行 127.0.0.1
    的手机端 4712 / 内置 Tailscale 代理 4720 / 电脑端 4711 端口）。
  - 2.3.0：修复手机端误显示电脑端页面；新建对话只保留文件夹选择；对话列表
    可直接删除；文件夹可删除；手机端默认低档位并可在对话顶部切换；
    设置里可清空数据/卸载；手机控制读屏更省节点；新增无障碍悬浮窗，
    显示状态并支持停止/插话（状态/停止免权限；浮窗插话需系统悬浮窗权限，
    未授权自动转聊天页输入；插话时暂停自动化，不会误点输入法）。
  - 2.2.4：电脑端页面资源在手机代理内缓存 10 分钟并 gzip；5G 下直接启动内置
    Tailscale（不再先等家里 Wi-Fi 超时），并按上次使用记录在 App 启动时预连接；
    日志显示直连/中继；代理连接复用。
  - 启动先到主页，可选「电脑端」（原来的远程模式，行为不变）或「手机端」
    （在手机本地用 proot + Alpine 跑 Node 和 Claude Code，不依赖电脑）。
  - 手机控制：开启无障碍后，Claude 可以通过 phone_* MCP 工具读取屏幕、
    点按、输入、滑动、打开 App；密码/支付/转账/验证码会交还用户。
  - 电脑端可一次配置多个地址（家里 Wi-Fi + Tailscale），App 按当前网络
    自动探测可用地址，换网络不用重新填。2.2.0 起支持内置 Tailscale（tsnet），
    不用安装 Tailscale App、不占 Android VPN。
  - 安装进度实时可见；GCS 下载过慢会自动切换 claude.ai / npm 国内镜像。
  - 手机端首次安装约 500 MB，需 1.8 GB 空间；详细步骤见
    `apk/双模式APK-安装说明.txt`。
  - 使用前提：手机为 arm64、Android 8+；DeepSeek API Key。
  - 手机端只监听 127.0.0.1，不影响电脑端和主客户端更新包。

## 目录

```
overlay/claude-gui/        手机页、图标、远程开关（复制到客户端目录）
server/                    server-1.1.28-remote.js（适配 1.1.28 的远程版服务端）
patch/                     server-1.1.28-remote.patch（干净版 → 远程版，供维护者参考）
install/                   一键安装器
android/                   Android 双模式源码（电脑远程 + 手机本地）
phone/                     手机端资源生成脚本和设计说明（Alpine/proot/Node）
pc/                        PC 端公网 IPv6 直连、无障碍一次性授权脚本
apk/                       双模式 APK、通用版 APK、安装说明
```

## 安装（其他用户）

前提：已经装好 Claude 工作台客户端 **1.1.28**，并且客户端能正常启动。

客户端本体（不含远程功能）从这里装：

- 全量一键部署 **1.1.21**（646 MB，包含 Python 3.13、PowerShell 7.6.9、jq、Electron；
  安装时自动配置环境）：
  <https://github.com/enneliang-stack/claude-workbench-update/releases/tag/v1.1.21>
  - 国内加速直链：
    <https://ghfast.top/https://github.com/enneliang-stack/claude-workbench-update/releases/download/v1.1.21/ClaudeWorkbench-Setup-1.1.21.exe>
- 装完后用客户端的「更新」按钮升级到 **1.1.28**（或者用 1.1.28 离线更新包）。
  1.1.28 是客户端增量更新（约 4.5 MB，不含 Python/PowerShell/jq）；
  新用户就是「1.1.21 全量包 → 1.1.28 更新」。
- 客户端 1.1.28 装好后再运行下面的远程安装器。

1. 把整个仓库下载/解压到任意目录（不要只拿 `server.js`）。
2. 双击 `install\一键安装手机远程控制.cmd`。
   - 安装器会自动找 `E:\AI_Claude\claude-gui`、用户目录下的 `AI_Claude\claude-gui` 等常见位置；
   - 找不到时会提示输入客户端目录；
   - 安装前会备份 `server.js` 和会被覆盖的手机页到 `data\backups\remote-install-*`；
   - 如果客户端版本不是 1.1.28，会拒绝安装（避免把不匹配的服务端换上去）。
3. 从托盘退出工作台，再双击客户端的 `启动界面.cmd` 重新打开。
4. 双击客户端的 `远程访问.cmd`，窗口会打印手机地址（带 token）。
5. 手机安装 `apk\Claude工作台-通用版-1.0.11.apk`；第一次打开把第 4 步的地址粘贴进去。
   也可以不用 APK：手机 Chrome 打开第 4 步的 HTTPS 地址，菜单里选「安装应用」。

关闭远程访问：双击客户端的 `关闭远程访问.cmd`。

## 手机 5G 加速：IPv6 直连

手机 5G 的运营商 NAT 通常是对称型，Tailscale 打不了 IPv4 洞，只能走 SFO 中继，
页面加载会有约 300ms 的额外往返。电脑 IPv4 如果是 CGNAT（本机路由器 WAN 是
100.66.x.x），端口映射也没用。

如果电脑和手机都有公网 IPv6，Tailscale 会优先直连。电脑网卡的 IPv6 协议
可能默认关闭，运行：

```text
pc\开启IPv6直连.cmd
```

脚本只开启已连接物理网卡的 `ms_tcpip6`，不做端口映射。输出里出现
240e / 2001 开头的地址、`tailscale netcheck` 显示 `IPv6: yes` 后，手机打开
电脑端，App 日志应显示「直连」。本机已验证 Tailscale 状态里出现
`claude-phone active; direct [240e:...]`。

如果路由器没有下发公网 IPv6，需要在路由器后台开启 IPv6 / IPv6 双栈。

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
