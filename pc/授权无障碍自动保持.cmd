@echo off
chcp 65001 >nul
setlocal
title Claude 工作台 · 无障碍自动保持（一次性授权）

set "ADB=adb"
where adb >nul 2>nul
if errorlevel 1 set "ADB=E:\AI_Claude\android-sdk\sdk\platform-tools\adb.exe"

echo.
echo 请先在手机上开启「开发者选项 → USB 调试」，并用数据线连接电脑。
echo 手机弹出「允许 USB 调试」时点允许。
echo.

"%ADB%" devices
echo.
"%ADB%" wait-for-device

echo 正在授权无障碍自动保持...
"%ADB%" shell pm grant com.missyao.claudeworkbench android.permission.WRITE_SECURE_SETTINGS
"%ADB%" shell appops set com.missyao.claudeworkbench ACCESS_RESTRICTED_SETTINGS allow
"%ADB%" shell dumpsys deviceidle whitelist +com.missyao.claudeworkbench >nul

echo.
echo 完成。以后系统关闭无障碍后，打开 Claude 工作台会自动重新开启；
echo 开机时也会自动恢复。现在可以拔掉数据线。
echo.
pause
