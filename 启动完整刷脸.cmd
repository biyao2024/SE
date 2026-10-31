@echo off
chcp 65001 >nul
setlocal
title 人脸考勤 - 完整模式
echo 完整模式：目标环境前后端、Python 3.11 算法与内置 H2。
echo 会检查固定依赖及模型文件；真实摄像头、人员录入和校准仍需验收。
echo 启动前请先运行“停止项目.cmd”，确认已有项目进程正常停止。
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch-target.ps1" -Mode Full
set "project_exit=%errorlevel%"
echo.
if "%project_exit%"=="0" (echo 服务监听已启动。请登录系统检查模型及人员库就绪状态。) else (echo 启动失败。请保留上方错误并查看项目 .runtime 日志。)
echo 关闭此窗口不会停止后台服务。
pause
exit /b %project_exit%
