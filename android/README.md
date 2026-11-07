# Android 通用版 APK 源码

这个壳只是 WebView；手机页面本身由电脑实时提供。
通用版不内置任何人的地址/token，首次打开让用户粘贴自己的连接地址。

## 构建

需要：

- JDK 17
- Android SDK：platform 34、build-tools 34.0.0
- Python 3

```bash
cd android/app
ANDROID_SDK=/path/to/android-sdk \
JAVA_HOME=/path/to/jdk-17 \
bash build.sh
```

产物：`android/app/ClaudeWorkbench.apk`。

如果要用已有签名覆盖安装旧版：

```bash
KS_FILE=/path/to/keystore.jks KS_PASS=****** bash build.sh
```

不提供 `KS_FILE` 时会在 `android/app/keystore.jks` 生成一个新 keystore；
用新签名装到已有旧版的手机上需要先卸载旧版。

## 目录

- `app/src/.../MainActivity.java`：WebView 壳、首次配置地址、文件选择、返回键处理。
- `app/src/.../DownloadsBridge.java`：App 私有下载目录、打开/删除。
- `app/src/.../DownloadProvider.java`：把 App 内文件以 content:// 交给查看器。
- `app/res/`：图标、主题、字符串。
- `app/AndroidManifest.xml`：包名、权限、版本号。

## 注意

- 不要把个人 Tailscale 主机名、局域网 IP、`gui-token.json` 写进源码。
- 仓库里预编译的 `apk/Claude工作台-通用版-1.0.11.apk` 是用
  `KS_FILE` 指向个人 keystore 构建的；如果你重新构建并生成新 keystore，
  签名会不同，需要先卸载旧 App。
- `apk/` 里不包含 Tailscale 安装包（105 MB），请从官网下载。
