# 手机端（本机运行 Claude Code）设计说明

目标：在现有「手机远程控制电脑」的 APK 里增加一个 **手机端** 模式，让 Claude Code
直接跑在手机上，和电脑端并列切换；电脑端保持原样。

## 结构

```
启动 App
  └─ 主页
      ├─ 电脑端：加载原来保存的远程地址（PC 上的 server.js，不改）
      └─ 手机端：
           EngineService（前台服务）
             └─ proot + Alpine（App 私有目录）
                  ├─ Node.js：/opt/workbench/server.js（由 PC 版 server.js 生成）
                  ├─ Claude Code：linux-arm64-musl 单文件
                  └─ phone-mcp.js（stdio MCP，转发到 App 的手机控制接口）
           PhoneAccessibilityService（用户手动开启）
             └─ PhoneControlServer：127.0.0.1 随机端口 + token
           WebView 打开 http://127.0.0.1:4712/m/index.html
```

手机端复用 PC 版的 `server.js` 和手机页 `m.html/m.js/m.css`，因此对话、工具卡片、
权限审批、会话列表、图片上传等行为与电脑端一致；只对 Linux/手机环境做最小改动：

- 只监听 `127.0.0.1`，关闭远程访问。
- `claude` 固定为 `/root/.local/bin/claude`。
- 余额接口直接请求 DeepSeek（不再依赖桌宠 sidecar）。
- 文件选择的快捷入口改为 `/workspace`、`/sdcard`。
- 手机端专属的「返回模式选择」浮钮（只在手机端页面注入，PC 页面不受影响）。

## 运行时与首次安装

2.4.6 起的完整 APK 约 194 MB，已经内置：

- Alpine minirootfs 3.24.2（约 3.8 MB 压缩）；
- 47 个 Alpine aarch64 包（bash/curl/jq/ripgrep/git/nodejs/npm 及全部依赖，
  约 38 MB 压缩）；
- Claude Code 2.1.319 `linux-arm64-musl` 原生包（约 144 MB 压缩，
  解压后 324 MB）。

首次点「手机端」时只做本地操作，不下载环境：

1. 从 APK asset 解出 Alpine（`installRootfs` 直接读 asset）。
2. `copyAssets` 把 `apk/*.apk` 和 `claude-musl.tgz` 复制到 App 私有目录。
3. proot 里执行 `apk index` + `apk add --no-network`，从本地 repo
   离线安装 Node/npm 和工具；失败才回退清华/官方在线源。
4. `tar -xzf /opt/workbench/claude-musl.tgz` 解出 `package/claude`，
   放到 `/root/.local/bin/claude`；失败才回退 GCS/官方脚本/npm 备用版。
5. 写入 `~/.claude/settings.json`（DeepSeek 地址/Key/模型），启动
   `node server.js`，WebView 打开 `127.0.0.1`。

安装成功后会自动删除手机目录里的 180 MB 离线包副本（APK 里仍有，
重装环境时会重新复制）。仍需约 1.8 GB 可用空间。

旧的 2.4.5 精简 APK 仍保留在线安装路径：Alpine/工具/Node 走清华镜像，
Claude Code 优先 GCS，慢速时切换 `claude.ai` 或 npmmirror 的 npm 备用版。

### 重新生成离线包

```bash
python phone/fetch-offline-packages.py
# 下载 Alpine rootfs + 47 个 aarch64 包到 phone/offline-apk/
# 然后把 Claude Code 原生 npm 包放到 phone/claude-musl.tgz：
# https://registry.npmmirror.com/@anthropic-ai/claude-code-linux-arm64-musl/-/claude-code-linux-arm64-musl-2.1.319.tgz
```

这些大文件都在 `.gitignore` 里，不进 git；构建时 `build-phone-assets.py`
会把它们复制进 `android/app/assets/phone/`。改了离线资源后要同步提高
`LocalEngine.ASSET_VERSION`。

## 文件放置

- `/workspace` → App 私有工作区（默认新对话目录）。
- `/sdcard` → 授权「所有文件访问」后是手机公共存储；否则是 App 专属外部目录。
- 对话记录在 `~/.claude/projects`，重装时先备份再恢复。

## 电脑端多地址自动探测

电脑端不再只存一个地址，而是保存一个列表（`PREF_URLS`，每行一个）。
主页点「电脑端」时，App 在后台按顺序探测这些地址，哪个返回工作台页面就用哪个：

- 家里 Wi-Fi 和 Tailscale 各配一条，换网络不用重新填。
- 探测成功后把该地址移到列表第一位并保存，下次优先尝试。
- 连接失败页有「打开 Tailscale」按钮；更彻底的做法是把 Tailscale 设为
  Android 的「始终开启 VPN」，出门自动保持连接。

## 手机本机文件夹选择

- `/workspace` 是 App 私有工作区，默认新对话目录，不在手机文件管理器里显示。
- `/sdcard` 在授予「所有文件访问」后对应 `/storage/emulated/0`；否则是 App 专属外部目录。
- 2.1.4 起，新对话面板的路径输入下方有「浏览手机文件夹」按钮，
  由 `phone/phone-folder-picker.js` 注入，调用 `/api/fs` 浏览目录，
  选中后写回路径并创建对话；服务端也会把 `/sdcard` 加入文件夹列表。
- 修改存储授权后需要停止/启动一次手机端，重新建立 `/sdcard` 绑定。

## 内置 Tailscale（tsnet 用户态代理）

2.2.0 起，`phone/tsproxy` 会编译成 linux-arm64 的 `tsproxy` 并随 APK 打包：

- App 在「设置与日志 → 内置 Tailscale」里保存 auth key 和电脑 Tailscale 地址
- `LocalEngine.startTailscaleProxy` 在 proot 里启动 `tsproxy`
- `tsproxy` 用 tsnet 加入 tailnet（设备名 `claude-phone`），在本机
  `127.0.0.1:4720` 提供反向代理，把请求转给电脑的 Tailscale 地址
- 不创建 VPN、不占 Android VPN 通道，不需要安装 Tailscale App
- 电脑端多地址探测时，先试已配置的 LAN/Tailscale 地址；都不通才启动内置代理，
  并把 `127.0.0.1:4720/...?token=...` 加入候选
- 首次需要 auth key；之后设备状态保存在 `data/tsnet`，auth key 可以留空
- 如果 tailnet 开了设备审批，需要在 Tailscale 后台批准 `claude-phone`
- 构建方法见 `phone/tsproxy/README.md`；修改代理后要重新生成二进制并
  再 bump `ASSET_VERSION`

## 手机控制（无障碍点按）

手机端启动 Claude 时，`server.js` 会加 `--mcp-config /opt/workbench/phone-mcp.json`，
Claude 因此看到 10 个 `phone_*` 工具：

- `phone_status` / `phone_ui`：读屏和当前界面
- `phone_tap` / `phone_tap_text` / `phone_type` / `phone_swipe`
- `phone_global`（back/home/recents/通知/快捷设置）
- `phone_open_app` / `phone_apps`
- `phone_screenshot`

调用链：Claude → `phone-mcp.js`（proot 内 stdio MCP）→
`http://127.0.0.1:<随机端口>`（App 内 `PhoneControlServer`）→
`PhoneAccessibilityService`。控制接口只监听 loopback，使用随机 token；
token/端口写在 `data/phone-control.json`，MCP 桥每次调用时读取。

安全策略：

- 用户必须在「设置与日志 → 手机控制」开启，并在系统无障碍设置里授权。
- 密码框直接拒绝输入；目标或当前页面涉及密码/支付/付款/转账/验证码/银行卡/
  免密/确认购买/提交订单时，工具返回 `needsUser`，要求交还用户。
- 每次操作写入 `phone.log`（类型输入只记长度，不记内容）。
- 关闭开关会立即停止控制接口；无障碍服务本身仍由系统管理。

## 构建

```bash
cd android/app
ANDROID_SDK=/path/to/sdk JAVA_HOME=/path/to/jdk17 \
KS_FILE=/path/to/keystore.jks KS_PASS=****** bash build.sh
```

构建前会自动运行 `phone/build-phone-assets.py`，从 `server/server-1.1.28-remote.js`
和 `overlay/claude-gui/public` 生成 `android/app/assets/phone/`。

修改 `server.js` 或手机页后，记得把 `LocalEngine.ASSET_VERSION` 加 1；
否则已经装过手机端的设备会继续使用旧的 `assets/phone` 副本。

## 已知限制 / 排查

- **后台被杀**：OriginOS 需要在电池设置里允许后台高耗电、允许自启动、最近任务锁定；
  Android 12+ 必要时用 adb 关闭 phantom process 限制。
- **proot 启动失败**：默认已设 `PROOT_NO_SECCOMP=1`；仍失败时看「设置与日志」里的
  `phone.log`，确认 libproot 是否被系统拦截。
- **下载失败**：GCS 在部分网络不可达时，app 会依次回退 claude.ai 和 npmmirror；
  三个都不通时需要代理/VPN，或把官方 `claude` 二进制手动放到手机再安装。
- **手机控制**：已经接入 AccessibilityService + 本地 MCP。限制：
  - 游戏、视频、部分安全键盘不暴露无障碍节点，读屏/按文字点击会失败；
    可以改用 `phone_screenshot` + 坐标 `phone_tap`，或手动操作。
  - 部分自定义输入框不支持 `ACTION_SET_TEXT`，`phone_type` 会提示手动输入。
  - Android 13+ 侧载应用的无障碍开关可能是灰的：先「允许受限设置」。
  - 从后台直接打开 App 在部分 ROM 上可能被拦；失败时先手动打开再让 Claude 操作。
  - 目前没有通知栏读取/通知回复，后续可以加 NotificationListenerService。
- **Claude Code 更新**：`DISABLE_AUTOUPDATER=1`，由 App 安装流程控制版本；
  重新安装会重新取官方最新版。
- **开源许可**：见 `android/app/assets/licenses/NOTICE.md`（proot GPL-2.0、
  talloc LGPL-3.0、libandroid-shmem BSD、Buddy Apache-2.0）。Claude Code 本体
  不随 APK 分发，由设备端从官方地址下载。
