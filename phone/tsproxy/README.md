# 内置 Tailscale 代理（tsproxy）

这是 App 内置的 **tsnet 用户态 Tailscale 反向代理**。

- 不创建 Android VPN，不占用系统 VPN 通道
- 以 `claude-phone` 设备加入 tailnet（需要用户自己的 auth key）
- 在手机 `127.0.0.1:4720` 提供 HTTP 反向代理
- 把请求转发到电脑的 Tailscale 地址（`TS_TARGET`）

## 环境变量

| 变量 | 说明 |
|---|---|
| `TS_AUTHKEY` | Tailscale auth key；首次必填，之后可留空使用已保存状态 |
| `TS_TARGET` | 电脑 origin，例如 `http://100.x.x.x:4711` 或 `https://电脑名.xxx.ts.net` |
| `TS_STATE_DIR` | tsnet 状态目录，App 内为 `/opt/workbench/data/tsnet` |
| `TS_LISTEN` | 本地监听地址，默认 `127.0.0.1:4720` |
| `TS_HOSTNAME` | 设备名，默认 `claude-phone` |
| `TS_FAKE_DEFAULT_IFACE` | Android 活动网卡；netmon patch 用它作为默认路由 |
| `TS_PREFER_DERP` | 可选 DERP 区域 ID；例如 `2` 表示强制 SFO。只影响中继路径，不阻止直连尝试 |

状态接口：`GET /__tsproxy/status`，返回 `{ok, up, error, target, direct, relay, peerOnline, cacheItems, cacheBytes}`。

## 性能

- `m.js` / `m.css` / `ref-digest.js` / 图标等静态资源在代理内缓存 10 分钟，
  同时给 WebView 返回 `Cache-Control: public, max-age=600`；第二次打开页面
  不再把这些文件走一遍 Tailscale 中继。
- 文本/JSON/JS/CSS 响应自动 gzip；SSE（`Cache-Control: no-transform`）不压缩、
  不缓冲，保证聊天流式输出。
- 到电脑的 HTTP 连接池：`MaxIdleConns=64`、`MaxIdleConnsPerHost=32`，
  减少每次请求重新握手。
- `direct=true` 表示直连；`direct=false` 且 `relay=sfo` 表示走中继。
  手机 5G 对称 NAT 下通常只能中继，这是线路限制。

## 构建

需要 Go 1.24+：

```bash
cd phone/tsproxy
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 \
  go build -trimpath -ldflags "-s -w" -o tsproxy-linux-arm64 .
```

把产物放到本目录后，`phone/build-phone-assets.py` 会把它复制进
`android/app/assets/phone/tsproxy`，App 首次启动/升级时由
`LocalEngine.copyAssets` 复制到 proot 的 `/opt/workbench/tsproxy`。

App 侧的启动、状态等待、停止在 `LocalEngine.startTailscaleProxy` /
`stopTailscaleProxy`；设置入口在「设置与日志 → 内置 Tailscale」。

## 注意

- `patch-netmon.py` 会在 `go mod vendor` 后给 netmon 加 `TS_FAKE_DEFAULT_IFACE` 支持；
  `build-tsproxy.ps1` 已自动执行 vendor + patch + build。
- 首次需要在 Tailscale 后台生成 auth key；如果 tailnet 开了设备审批，
  还需要在后台批准 `claude-phone`。
- auth key 只保存在手机本地，不要发人。
- tsnet 走 Tailscale 协调服务器；国内网络稳定性和 Tailscale App 类似。
- 该代理和手机端 Node 服务都跑在同一个 proot 里；如果 Go 运行时在
  proot 下启动失败，运行日志里会有 `[tsnet]` / `[proxy]` 相关信息。
- Tailscale 许可证见 `android/app/assets/licenses/tailscale-LICENSE.txt`。
