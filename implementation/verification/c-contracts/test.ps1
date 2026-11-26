$ErrorActionPreference = 'Stop'
$backend = Resolve-Path (Join-Path $PSScriptRoot '../../backend')
$work = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ('campus-c-' + [guid]::NewGuid()))
Copy-Item (Join-Path $backend 'pom.xml') $work
Copy-Item (Join-Path $PSScriptRoot 'src') $work -Recurse
Copy-Item (Join-Path $backend 'src') $work -Recurse -Force
& mvn -f (Join-Path $work 'pom.xml') test
exit $LASTEXITCODE
