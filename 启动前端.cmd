@echo off
chcp 65001 >nul
cd /d "%~dp0implementation\frontend"
where node >nul 2>nul
if errorlevel 1 (
 echo 请先安装Node.js 20.19或更高版本，并将node加入PATH。
 pause
 exit /b 1
)
node serve.mjs
pause
