# 第三方组件说明 / Third-party notices

Claude 工作台手机端（双模式）在 Android 手机上运行一个 Alpine Linux 用户空间，
其中包含以下开源组件。各组件版权归原作者所有。

## proot（GPL-2.0）

- 用途：在不 root 的 Android 手机上通过 ptrace 提供 Linux 根文件系统。
- 来源：Termux proot / Buddy 的 fork，见 <https://github.com/termux/proot> 与
  <https://github.com/ghorbelhamdi/buddy-android>。
- 许可证全文：`proot-COPYING.txt`、`proot-LICENSE.txt`。
- 本项目以预编译动态库 `libproot.so`、`libproot-loader.so` 的形式随 APK 分发；
  如需对应源码，请使用上述上游仓库（与 Buddy 的 `third_party/proot` 构建一致）。

## talloc（LGPL-3.0）

- 用途：proot 的内存分配依赖。
- 许可证全文：`talloc-LICENSE.txt`。
- 来源：<https://talloc.samba.org/>。

## libandroid-shmem（BSD-3-Clause）

- 用途：Android 上的共享内存兼容层，proot 使用。
- 许可证全文：`libandroid-shmem-LICENSE.txt`。

## Buddy（Apache-2.0）

- 用途：参考并复用了其 Alpine + proot 的运行时启动方式、根文件系统解包思路。
- 项目：<https://github.com/ghorbelhamdi/buddy-android>
- 许可证全文：`buddy-LICENSE.txt`。

## Alpine Linux / Node.js / Claude Code

- Alpine minirootfs 在首次安装时从 Alpine 官方镜像下载，包含各类开源包（busybox、
  musl、apk-tools、nodejs、npm、ripgrep、git、curl 等），各自遵循其许可证。
- Node.js 通过 Alpine 的软件仓库安装（MIT 等）。
- Claude Code 是 Anthropic 的商业软件，**不随 APK 打包**。手机端在首次安装时从
  Anthropic 官方发布地址（storage.googleapis.com/claude-code-dist-…）下载
  linux-arm64-musl 单文件，并校验官方 manifest 中的 SHA-256。使用它需要遵守
  Anthropic 的商用条款；本项目只做个人设备上的本机运行与界面整合。

## 内置 Tailscale（tsnet）

- 用户态 Tailscale 代理 `tsproxy` 使用 Tailscale 的 tsnet 库，BSD-3-Clause。
  许可证见 `tailscale-LICENSE.txt`，专利条款见 `tailscale-PATENTS.txt`。
- 该二进制由 Go 编译，包含 WireGuard、gVisor 等依赖，各自遵循其上游许可证。
- 使用它需要用户自己的 Tailscale 账号和 auth key；设备会以 `claude-phone` 出现在 tailnet。
