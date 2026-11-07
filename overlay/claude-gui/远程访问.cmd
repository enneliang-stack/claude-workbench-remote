@echo off
chcp 65001 >nul
cd /d "%~dp0"
title Claude Code 工作台 - 远程访问
node tools\remote-control.js %*
echo.
pause
