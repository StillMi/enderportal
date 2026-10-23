# 查询仓库里真实存在的版本号，用来填 build.gradle / gradle.properties
# 用法：右键"使用 PowerShell 运行"，或  pwsh -File tools\find-versions.ps1

$ErrorActionPreference = 'Continue'
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }

$feeds = @(
    @{ Name = 'NeoGradle 插件 (net.neoforged.gradle:userdev)'
       Url  = 'https://maven.neoforged.net/releases/net/neoforged/gradle/userdev/maven-metadata.xml'
       Filter = $null },
    @{ Name = 'NeoForge 本体 (21.1.x)'
       Url  = 'https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml'
       Filter = '^21\.1\.' },
    @{ Name = 'ModDevGradle 插件 (备选方案)'
       Url  = 'https://plugins.gradle.org/m2/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/maven-metadata.xml'
       Filter = $null }
)

$anyFailed = $false

foreach ($feed in $feeds) {
    Write-Host ''
    Write-Host "=== $($feed.Name) ===" -ForegroundColor Cyan
    try {
        $resp = Invoke-WebRequest -Uri $feed.Url -UseBasicParsing -TimeoutSec 30
        $xml = [xml]$resp.Content
        $versions = @($xml.metadata.versioning.versions.version)
        if ($feed.Filter) {
            $versions = @($versions | Where-Object { $_ -match $feed.Filter })
        }
        if ($versions.Count -eq 0) {
            Write-Host '  仓库可访问，但没有匹配的版本。' -ForegroundColor Yellow
            continue
        }
        Write-Host ("  可用数量: " + $versions.Count)
        Write-Host ("  最新:     " + $versions[-1]) -ForegroundColor Green
        Write-Host ("  最近若干: " + (($versions | Select-Object -Last 12) -join ', '))
    }
    catch {
        $anyFailed = $true
        Write-Host "  取不到: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host '  -> 这台机器访问不了该仓库（网络问题），不是版本号写错。' -ForegroundColor Yellow
    }
}

Write-Host ''
if ($anyFailed) {
    Write-Host '结论：至少一个仓库连不上。需要给 Gradle 配代理，例如在 gradle.properties 里加：' -ForegroundColor Yellow
    Write-Host '  systemProp.https.proxyHost=127.0.0.1'
    Write-Host '  systemProp.https.proxyPort=7890'
    Write-Host '（端口换成你自己的代理端口），或者开启加速器后再构建。'
}
else {
    Write-Host '结论：网络正常。把上面"最新/最近若干"里的版本号填进 build.gradle 和 gradle.properties。' -ForegroundColor Green
}
