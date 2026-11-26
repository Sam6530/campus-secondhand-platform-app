param([switch]$Stop,[switch]$NoBrowser)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$runDir=Join-Path $PSScriptRoot '.run'
$statePath=Join-Path $runDir 'processes.json'

function Stop-OwnedProcesses($entries) {
    foreach($entry in $entries) {
        $process=Get-Process -Id $entry.processId -ErrorAction SilentlyContinue
        if($process -and $process.StartTime.ToUniversalTime().ToString('o') -eq $entry.started) {
            Stop-Process -Id $entry.processId
        }
    }
}
if($Stop) {
    if(Test-Path -LiteralPath $statePath) {
        Stop-OwnedProcesses (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json)
        Remove-Item -LiteralPath $statePath
    }
    Write-Host '已停止本项目启动的前后端进程。MySQL数据保留。'
    exit 0
}
$configPath=Join-Path $projectRoot '.env.local'
if(Test-Path -LiteralPath $configPath) {
    foreach($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
        }
    }
}
if(-not $env:DB_URL -or -not $env:DB_USERNAME) {throw '请在项目根目录.env.local配置DB_URL、DB_USERNAME、DB_PASSWORD。'}
$javaExe=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java).Source}
$nodeExe=if($env:NODE_BIN){$env:NODE_BIN}else{(Get-Command node).Source}
$backendPort=if($env:SERVER_PORT){[int]$env:SERVER_PORT}else{18080}
$frontendPort=if($env:FRONTEND_PORT){[int]$env:FRONTEND_PORT}else{5173}
foreach($port in @($backendPort,$frontendPort)) {
    if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {throw "端口${port}已被占用，请先停止本项目或更换端口。"}
}
if(-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'backend/target/campus-resale-backend.jar'))) {throw '请先构建后端：cd implementation/backend；mvn -DskipTests package'}
if(-not(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'frontend/dist/index.html'))) {throw '请先构建前端：cd implementation/frontend；pnpm install --frozen-lockfile；pnpm build'}
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$entries=@()
try {
    $env:SERVER_PORT=[string]$backendPort
    $backend=Start-Process -FilePath $javaExe -ArgumentList '-Xmx512m','-jar','target/campus-resale-backend.jar' -WorkingDirectory (Join-Path $PSScriptRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'backend.log') -RedirectStandardError (Join-Path $runDir 'backend-error.log')
    $entries+=@{processId=$backend.Id;started=$backend.StartTime.ToUniversalTime().ToString('o')}
    $ready=$false
    for($i=0;$i -lt 60;$i++) {
        if($backend.HasExited){throw '后端启动失败，请查看implementation/.run日志。'}
        try {Invoke-RestMethod "http://127.0.0.1:$backendPort/api/health" -TimeoutSec 1 | Out-Null;$ready=$true;break}catch{Start-Sleep -Milliseconds 500}
    }
    if(-not $ready){throw '后端启动超时，请检查数据库地址和implementation/.run日志。'}
    $env:API_PROXY_TARGET="http://127.0.0.1:$backendPort"
    $env:FRONTEND_PORT=[string]$frontendPort
    $env:FRONTEND_HOST='0.0.0.0'
    $frontend=Start-Process -FilePath $nodeExe -ArgumentList 'serve.mjs' -WorkingDirectory (Join-Path $PSScriptRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'frontend.log') -RedirectStandardError (Join-Path $runDir 'frontend-error.log')
    $entries+=@{processId=$frontend.Id;started=$frontend.StartTime.ToUniversalTime().ToString('o')}
    $ready=$false
    for($i=0;$i -lt 30;$i++) {
        if($frontend.HasExited){throw '前端启动失败，请查看implementation/.run日志。'}
        try {Invoke-WebRequest "http://127.0.0.1:$frontendPort" -UseBasicParsing -TimeoutSec 1 | Out-Null;$ready=$true;break}catch{Start-Sleep -Milliseconds 300}
    }
    if(-not $ready){throw '前端启动超时。'}
    $entries | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
    Write-Host "运行地址：http://127.0.0.1:${frontendPort}；组员可访问http://本机局域网IP:$frontendPort"
    if(-not $NoBrowser){Start-Process "http://127.0.0.1:$frontendPort"}
} catch {Stop-OwnedProcesses $entries;throw}
