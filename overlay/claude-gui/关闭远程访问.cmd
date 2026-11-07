@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Claude Code 工作台 - 关闭远程访问
node tools\remote-control.js off
echo.
pause
