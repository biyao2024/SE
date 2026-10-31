@echo off
chcp 65001 >nul
setlocal
title 人脸考勤 - 停止项目
echo 仅停止本目录启动并记录的项目进程；MySQL 独立服务由系统管理。
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-target.ps1" -Mode Stop
set "project_exit=%errorlevel%"
echo.
if "%project_exit%"=="0" (echo 项目停止完成。) else (echo 停止未完成，请保留错误信息。不会强行结束无法确认归属的进程。)
pause
exit /b %project_exit%
