@echo off
chcp 65001 >nul
title 问书 DocMind - 启动中...

echo ============================================
echo           问书 DocMind 一键启动
echo ============================================
echo.

:: 第1步：启动数据库
echo [1/3] 启动数据库...
docker compose -f deploy\docker-compose.dev.yml up -d 2>nul
if %errorlevel% neq 0 (
    echo   数据库启动失败！请确认 Docker Desktop 已打开。
    pause
    exit /b 1
)
echo   数据库已就绪（PostgreSQL + Redis）
echo.

:: 第2步：读取 API Key
echo [2/3] 读取 API Key...
set "DEEPSEEK_API_KEY="
for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
    if /i "%%a"=="DEEPSEEK_API_KEY" set "DEEPSEEK_API_KEY=%%b"
)
if "%DEEPSEEK_API_KEY%"=="" (
    echo   未找到 API Key！请编辑 .env 文件填入 DEEPSEEK_API_KEY=sk-你的key
    notepad .env
    pause
    exit /b 1
)
echo   API Key 已加载
echo.

:: 第3步：启动后端
echo [3/3] 启动后端（首次启动约需 10 秒）...
echo.
start /b "" http://localhost:8080

cd backend
E:\springboot\tools\apache-maven-3.9.16\bin\mvn spring-boot:run -s ..\tools\settings.xml -DskipTests

echo.
echo ============================================
echo   项目已停止。按任意键关闭窗口。
echo ============================================
pause >nul
