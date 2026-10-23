<#
用本机 JDK 直接编译模组源码（替代跑不起来的 Gradle）。

沙箱里 Gradle 的 native-platform 加载失败，所以这里直接调 javac，
classpath 由 NeoForm 的 recompiled jar + NeoForge + client-extra + Gradle 依赖缓存拼成。

用法：
    pwsh -File tools\compile.ps1

产物：build\classes\**\*.class（之后用 tools\package.py 打包）
#>

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jdk  = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr\bin'
$javac = Join-Path $jdk 'javac.exe'
if (-not (Test-Path $javac)) {
    throw "找不到 javac：$javac（改脚本里的 `$jdk）"
}

# javac 处理不了中文路径的 argfile，所以全部换成相对路径 + 正斜杠
$cp = @(
    'build/neoForm/neoFormJoined1.21.1-20240808.144430/steps/packRecomp/output.jar',
    '.gradle/repositories/ng_dummy_ng/net/neoforged/neoforge/21.1.72/neoforge-21.1.72.jar',
    '.gradle/repositories/ng_dummy_ng/net/minecraft/client/1.21.1/client-1.21.1-client-extra.jar'
)
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
if (Test-Path $cache) {
    $cp += Get-ChildItem $cache -Recurse -Filter *.jar |
           Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
           ForEach-Object { $_.FullName.Replace('\', '/') }
}

$sources = Get-ChildItem (Join-Path $root 'src\main\java') -Recurse -Filter *.java |
           ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') }

$classes = Join-Path $root 'build\classes'
if (Test-Path $classes) { Remove-Item $classes -Recurse -Force }
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$cpString = '"' + ($cp -join ';') + '"'
$arguments = @(
    '-d', 'build/classes',
    '--release', '21',
    '-encoding', 'UTF-8',
    '-proc:none',
    '-Xlint:all',
    '-classpath', $cpString
) + $sources

$argFile = Join-Path $env:TEMP 'enderportal-javac.txt'
Set-Content -Path $argFile -Value $arguments -Encoding ASCII

Push-Location $root
try {
    & $javac "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }
    $count = (Get-ChildItem $classes -Recurse -Filter *.class | Measure-Object).Count
    Write-Host "编译完成：$count 个 class -> build\classes"
}
finally {
    Pop-Location
}
