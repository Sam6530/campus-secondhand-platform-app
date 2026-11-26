# 构建脚本（V1）：构建后端；前端加入后由 A 在此补充前端构建。
# 只用真实可用的工具：找不到 Maven 或构建失败时明确报错，不打印虚假成功。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
$backend = Join-Path $root 'backend'
if (-not (Test-Path -LiteralPath $backend)) {
    throw "未找到后端目录：$backend"
}

$mvn = $null
$mvnCommand = Get-Command 'mvn' -ErrorAction SilentlyContinue
if ($mvnCommand) {
    $mvn = $mvnCommand.Source
} elseif ($env:MAVEN_HOME) {
    $candidate = Join-Path $env:MAVEN_HOME 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $candidate) {
        $mvn = $candidate
    }
}
if (-not $mvn) {
    throw '未找到 Maven。请安装 Maven 3.9+ 并加入 PATH，或设置 MAVEN_HOME（本机实测版本 3.9.9）。'
}

Write-Host "使用 Maven：$mvn"
Push-Location -LiteralPath $backend
try {
    & $mvn -B package
    if ($LASTEXITCODE -ne 0) {
        throw "后端构建失败，退出码 $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$jar = Join-Path $backend 'target\campus-resale-backend.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw "构建结束但未找到产物：$jar"
}
Write-Host "后端构建完成：$jar"

