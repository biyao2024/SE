@echo off
chcp 65001 >nul
setlocal
title 人脸考勤 - 业务模式
echo 业务模式：启动前后端与内置 H2，不启动人脸算法。
echo 需要项目目标版本；完整刷脸请使用“启动完整刷脸.cmd”。
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-target.ps1" -Mode Business
set "project_exit=%errorlevel%"
echo.
if "%project_exit%"=="0" (echo 操作完成。关闭此窗口不会停止后台服务。) else (echo 启动失败。请保留上方错误并查看项目 .runtime 日志。)
echo 需要停止服务时，请运行“停止项目.cmd”。
pause
exit /b %project_exit%
