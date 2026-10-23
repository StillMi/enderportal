# 在 GitHub 上创建 v1.02 release 并上传 jar 附件。
# 用法： .\tools\create-release.ps1 -Token <PAT>
#
# 安全说明：token 只在内存里用，不写入任何文件。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Tag = 'v1.02',
    [string]$Title = 'Ender Portal Stone v1.02',
    [string]$NotesFile = 'RELEASE_NOTES_v1.02.md',
    [string]$AssetPath = 'build/libs/enderportal-1.02.jar'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$repo = 'StillMi/enderportal'
$api = 'https://api.github.com'

$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'           = 'enderportal-release'
}

$notesPath = Join-Path $root $NotesFile
$assetFull = Join-Path $root $AssetPath
if (-not (Test-Path $notesPath)) { throw "找不到发布说明：$notesPath" }
if (-not (Test-Path $assetFull)) { throw "找不到 jar：$assetFull" }

$body = Get-Content -Raw -Encoding UTF8 $notesPath

# 1) 创建 release（会自动建 tag）
Write-Host "==> 创建 release $Tag ..."
$payload = @{
    tag_name         = $Tag
    target_commitish = 'main'
    name             = $Title
    body             = $body
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json -Depth 5

$release = Invoke-RestMethod -Uri "$api/repos/$repo/releases" -Headers $headers `
    -Method Post -Body $payload -ContentType 'application/json'

Write-Host "    $($release.html_url)"

# 2) 上传 jar 附件
$assetName = Split-Path -Leaf $assetFull
Write-Host "==> 上传附件 $assetName ..."
$uploadUrl = ($release.upload_url -replace '\{.*\}', '') + "?name=$assetName&label=Ender+Portal+Stone+v1.02"

$uploadHeaders = $headers.Clone()
$uploadHeaders['Content-Type'] = 'application/java-archive'

$asset = Invoke-RestMethod -Uri $uploadUrl -Headers $uploadHeaders -Method Post `
    -InFile $assetFull

Write-Host "    $($asset.browser_download_url)  ($($asset.size) bytes)"
Write-Host ''
Write-Host "完成：$($release.html_url)" -ForegroundColor Green
