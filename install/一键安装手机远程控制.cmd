@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Claude 工作台 - 安装手机远程控制
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-remote.ps1" %*
echo.
pause
