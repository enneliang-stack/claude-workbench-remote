# PC 端：开启 IPv6 直连

手机 5G 的运营商 NAT 通常是对称型，Tailscale 打不了 IPv4 的洞，只能走
SFO 中继（实测往返约 300ms，偶尔丢包）。电脑这边如果是 CGNAT（路由器 WAN
地址是 100.64.x.x），做端口映射也没用。

更快的办法是让电脑和手机都走公网 IPv6：

- 手机 5G 一般自带公网 IPv6；
- 电脑的网卡默认可能把 IPv6 协议关掉了；
- 两边都有 IPv6 时，Tailscale 会优先直连，不再经过 SFO 中继。

运行 `开启IPv6直连.cmd`（会弹一次 UAC），脚本只做一件事：
给已连接的物理网卡启用 `ms_tcpip6`，不映射端口、不开放防火墙。

## 验证

1. 脚本输出里应看到 `240e:...` / `2001:...` 这样的公网 IPv6；
   `tailscale netcheck` 应显示 `IPv6: yes`。
2. 手机打开 App → 电脑端；日志应显示：
   `内置 Tailscale 已连接（直连）`
   如果仍显示 `中继 sfo`，说明路由器/运营商 IPv6 防火墙还没放行，
   或者手机当前网络没有 IPv6。
3. 电脑上也可以直接验证：

   ```powershell
   tailscale ping claude-phone
   ```

   直连会显示 `via [240e:...]:41641` 之类的地址；中继会显示 `via DERP(sfo)`。

## 路由器没有下发 IPv6

脚本会提示。需要登录路由器后台开启 IPv6 / IPv6 双栈（不同品牌路径不同，
通常在“网络设置 → IPv6”或“高级设置 → IPv6”）。开启后重新运行脚本。

## 关回去

`ncpa.cpl` → 对应网卡右键属性 → 取消勾选“Internet 协议版本 6 (TCP/IPv6)”。

---

# PC 端：一次性授权无障碍自动保持

Android 不允许普通 App 自己打开无障碍，系统在重装/强制停止/省电清理后可能
把开关收回。运行 `授权无障碍自动保持.cmd`，用一次 ADB 给 App 授予
`WRITE_SECURE_SETTINGS`，之后 App 会在启动和开机时自动把无障碍开关写回。

前提：手机已开启「开发者选项 → USB 调试」，用数据线连接电脑，并在手机上
允许 USB 调试。

脚本会执行（adb 不在 PATH 时自动使用 `E:\AI_Claude\android-sdk\sdk\platform-tools\adb.exe`）：

```bat
adb shell pm grant com.missyao.claudeworkbench android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.missyao.claudeworkbench ACCESS_RESTRICTED_SETTINGS allow
adb shell dumpsys deviceidle whitelist +com.missyao.claudeworkbench
```

授权后如果系统仍把无障碍开关置灰，再到 系统设置 → 应用 → Claude 工作台 →
右上角 ⋮ → 允许受限设置。覆盖安装新 APK 后，Android 仍会关闭一次无障碍，
但打开 App 时会被自动恢复。
