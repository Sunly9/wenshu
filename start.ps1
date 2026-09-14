# 问书 DocMind 一键启动
# 双击 启动项目.bat 会调用这个脚本

Set-Location E:\springboot\project3

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "         DocMind - Starting..." -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

# 启动数据库
Write-Host "`n[1/3] Starting database..." -ForegroundColor Yellow
docker compose -f deploy/docker-compose.dev.yml up -d 2>$null
Write-Host "  Database ready" -ForegroundColor Green

# 读取 API Key
Write-Host "`n[2/3] Loading API Key..." -ForegroundColor Yellow
$envContent = Get-Content .env -ErrorAction SilentlyContinue
foreach ($line in $envContent) {
    if ($line -match "^DEEPSEEK_API_KEY=(.+)$") {
        $env:DEEPSEEK_API_KEY = $Matches[1].Trim()
    }
}
if ($env:DEEPSEEK_API_KEY) {
    Write-Host "  API Key loaded" -ForegroundColor Green
} else {
    Write-Host "  Key not found! Opening .env..." -ForegroundColor Red
    notepad .env
    exit
}

# 启动后端
Write-Host "`n[3/3] Starting backend..." -ForegroundColor Yellow
Write-Host "  Browser will open at http://localhost:8080`n" -ForegroundColor Gray

Start-Process "http://localhost:8080"
Set-Location backend
& "E:\springboot\tools\apache-maven-3.9.16\bin\mvn.cmd" spring-boot:run -s ..\tools\settings.xml -DskipTests

Write-Host "`nStopped. Press any key to close."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
