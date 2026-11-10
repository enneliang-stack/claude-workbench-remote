# Claude 工作台 · 手机 App / APK

1.2.0 起手机功能已经并入主客户端，本仓库不再提供「在电脑上叠加远程功能」的安装器。
现在这里负责：

- Android App 源码与构建（`android/`、`phone/`）
- 手机页源文件（`overlay/claude-gui/public/`，构建时打进 APK）
- 手机本机 server（`server/`，构建时生成 `assets/phone/server.js`）
- APK Release 和 `apk/update.json`

电脑端使用方式：安装 1.2.0+ 的统一客户端，侧栏「手机连接」里开启访问并复制地址；
手机 App 粘贴地址即可。同一 Wi-Fi 不需要 Tailscale 账号。

## 最新 APK（2.4.27 精简 / 2.4.21 完整）

- 完整包（新用户/未装环境）：`ClaudeWorkbench-full.apk`，2.4.21，约 194 MB
  SHA-256：`4177f27dc3e3446f50130fb2ea3cbfc5731578247a37fc7eba2314162342e53e`
  下载：`https://github.com/enneliang-stack/claude-workbench-remote/releases/download/v2.4.21/ClaudeWorkbench-full.apk`
- 精简包（已装手机环境）：`ClaudeWorkbench-slim.apk`，2.4.27，约 8.1 MB
  SHA-256：`bd60d61941ebd4371c8fc64b309101f5371c3c95304377f4abd125229172d8b1`
- 2.4.27 返回主页不再重复显示「启动内置 Tailscale」：代理还在运行就直接复用探测；
  Wi-Fi 下并行测速所有已配置地址，自动用首字节最快的一条；蜂窝网络仍优先内置 Tailscale。
- 2.4.26 修复 App 内更新后点「打开」再进电脑端首次连接失败：更新前主动停掉旧内置
  Tailscale，更新后复用已连接代理；进电脑端前等待网络校验通过，不再刚装完就探测。
- 2.4.25 修复压缩摘要被误当成引用卡片：摘要正文里只是提到 `<gui-reference-context>`
  标签时，不再截断并显示成「引用对话」；真正的压缩摘要仍只显示「上下文对话已压缩」一行。
- 2.4.24 内置 Tailscale 连接超时后自动重试一次，不用再重启 App；日志默认只看报错，
  复制也只复制报错；日志页自动刷新不再把正在上滑的页面拉回顶部。
- 2.4.23 手机端通知栏显示任务运行中/N 个任务已完成/空闲中；普通手机本机任务也显示悬浮窗
  （需手机控制/无障碍开启）；远程电脑页任务不计入手机端状态，避免重叠。
- 2.4.22 修复日志页自动刷新强制滚到底、通知正文误跳日志、电脑端误显示「手机端」、
  内置 Tailscale peerapi nil listener 崩溃；完整包继续保持 2.4.21，新用户装完再更新精简包。
- 2.4.21 闪烁光标改为跟随最新一句白色阶段总结，只最新那一句闪；手机 App 的
  远程电脑页显示「电脑端」、本机页显示「手机端」，列表和对话页都有。
- 2.4.20 恢复手机页运行进度白字后面的闪烁光标；轮询刷新同一句进度时不再重置动画。
- 2.4.19 修复手机页进度不同步：SSE 在手机网络/内置 Tailscale 代理下偶尔
  「连接还在、事件不到」，阶段总结和运行状态不更新；现在运行中额外轮询实时快照。
- 2.4.18 修复引用对话后消息被误判成压缩摘要、长消息默认收起、发送后立即显示。
- 完整包超过 GitHub 普通文件 100 MB 限制，走 Releases；
  App 的「检查更新」读取 `apk/update.json`。
- 下载：<https://github.com/enneliang-stack/claude-workbench-remote/releases/latest>
- 国内加速：<https://ghfast.top/https://github.com/enneliang-stack/claude-workbench-remote/releases/latest>

## 历史版本

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
