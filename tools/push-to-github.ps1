# 用 PAT 在 GitHub 上建仓库并推送本项目的辅助脚本。
# 用法： .\tools\push-to-github.ps1 -Token <PAT> [-RepoName enderportal] [-Private]
#
# 安全说明：
#   - token 只在内存里用，不会写进 .git/config 的 remote URL。
#   - 推送时通过临时的 http.extraheader 传递，推完立即清掉。
#   - 用完请在 GitHub 上吊销这个 token。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$RepoName = 'enderportal',
    [string]$Description = 'Minecraft 1.21.1 (NeoForge) mod: a craftable stone that opens a linked portal pair to a different biome.',
    [switch]$Private
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $root '.git'))) {
    throw "找不到 git 仓库：$root"
}

$apiVersion = '2022-11-28'
$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = $apiVersion
    'User-Agent'           = 'enderportal-push-script'
}

# 1) 确认 token 属于谁
Write-Host '==> 读取账号信息 ...'
$me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers -Method Get
$login = $me.login
Write-Host "    登录为：$login"

# 2) 建仓库（已存在则跳过）
Write-Host "==> 创建仓库 $login/$RepoName ..."
$body = @{
    name        = $RepoName
    description = $Description
    private     = [bool]$Private
    has_issues  = $true
    has_wiki    = $false
    auto_init   = $false
} | ConvertTo-Json

try {
    $repo = Invoke-RestMethod -Uri 'https://api.github.com/user/repos' -Headers $headers `
        -Method Post -Body $body -ContentType 'application/json'
    Write-Host "    已创建：$($repo.html_url)"
} catch {
    $resp = $_.Exception.Response
    if ($resp -and [int]$resp.StatusCode -eq 422) {
        Write-Host '    仓库已存在，直接推送。'
        $repo = Invoke-RestMethod -Uri "https://api.github.com/repos/$login/$RepoName" -Headers $headers -Method Get
    } else {
        throw
    }
}

$cloneUrl = "https://github.com/$login/$RepoName.git"

# 3) 配置 remote（URL 里不带 token）
Write-Host '==> 配置 remote ...'
$existing = & git -C $root remote 2>$null
if ($existing -contains 'origin') {
    & git -C $root remote set-url origin $cloneUrl
} else {
    & git -C $root remote add origin $cloneUrl
}

# 4) 用临时 header 推送，避免 token 落盘
Write-Host '==> 推送 main ...'
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$Token"))
try {
    & git -C $root -c "http.https://github.com/.extraheader=Authorization: Basic $basic" `
        push -u origin main
    if ($LASTEXITCODE -ne 0) { throw "git push 失败（exit $LASTEXITCODE）" }
} finally {
    $basic = $null
}

Write-Host ''
Write-Host "完成：$($repo.html_url)" -ForegroundColor Green
Write-Host '提醒：请到 GitHub 设置里吊销刚才使用的 token。' -ForegroundColor Yellow
