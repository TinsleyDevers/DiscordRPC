# Uploads every jar described in release.json to Modrinth and CurseForge in
# one go, so a release is one command instead of fourteen web forms.
#
# Setup, once:
#   Modrinth:   create a PAT at https://modrinth.com/settings/pats with the
#               "Create versions" scope, then:  $env:MODRINTH_TOKEN = "..."
#   CurseForge: generate an API token at
#               https://legacy.curseforge.com/account/api-tokens, then:
#               $env:CURSEFORGE_TOKEN = "..."
#               Also fill in curseforge.projectId in release.json (the number
#               shown on your project page under "About Project").
#
# Usage:
#   .\publish.ps1 -DryRun         see what would be uploaded
#   .\publish.ps1                 upload everywhere
#   .\publish.ps1 -SkipCurseforge upload to Modrinth only

param(
    [string]$Manifest = "$PSScriptRoot\release.json",
    [switch]$DryRun,
    [switch]$SkipModrinth,
    [switch]$SkipCurseforge
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

$spec = Get-Content $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
$root = Split-Path $Manifest -Parent
$changelog = ""
if ($spec.changelog -and (Test-Path (Join-Path $root $spec.changelog))) {
    $changelog = Get-Content (Join-Path $root $spec.changelog) -Raw -Encoding UTF8
}

$modrinthToken = $env:MODRINTH_TOKEN
$curseforgeToken = $env:CURSEFORGE_TOKEN
if (-not $SkipModrinth -and -not $modrinthToken) {
    Write-Warning "MODRINTH_TOKEN is not set, skipping Modrinth."
    $SkipModrinth = $true
}
if (-not $SkipCurseforge -and -not $curseforgeToken) {
    Write-Warning "CURSEFORGE_TOKEN is not set, skipping CurseForge."
    $SkipCurseforge = $true
}
if (-not $SkipCurseforge -and -not $spec.curseforge.projectId) {
    Write-Warning "curseforge.projectId is empty in release.json, skipping CurseForge."
    $SkipCurseforge = $true
}

$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromMinutes(5)

# Modrinth wants project IDs, humans write slugs. Resolve and cache. The
# lookup sends the token when set, draft projects 404 for anonymous requests.
$slugCache = @{}
function Resolve-ModrinthId($slugOrId) {
    if ($slugCache.ContainsKey($slugOrId)) { return $slugCache[$slugOrId] }
    try {
        $req = [System.Net.Http.HttpRequestMessage]::new('GET', "https://api.modrinth.com/v2/project/$slugOrId")
        if ($modrinthToken) { $req.Headers.Add("Authorization", $modrinthToken) }
        $resp = $http.SendAsync($req).Result
        if (-not $resp.IsSuccessStatusCode) { throw "HTTP $([int]$resp.StatusCode)" }
        $id = ($resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json).id
        $slugCache[$slugOrId] = $id
        return $id
    } catch {
        Write-Warning "Could not resolve Modrinth project '$slugOrId'"
        return $null
    }
}

# CurseForge identifies Minecraft versions, loaders, environments and Java
# versions by numeric id. Fetch the table once and match by name.
$cfVersions = $null
function Get-CurseforgeVersionIds($entry) {
    if ($null -eq $script:cfVersions) {
        $req = [System.Net.Http.HttpRequestMessage]::new('GET', "https://minecraft.curseforge.com/api/game/versions")
        $req.Headers.Add("X-Api-Token", $curseforgeToken)
        $resp = $http.SendAsync($req).Result
        $script:cfVersions = $resp.Content.ReadAsStringAsync().Result | ConvertFrom-Json
    }
    $wanted = New-Object System.Collections.Generic.List[string]
    foreach ($v in $entry.gameVersions) { $wanted.Add($v) }
    foreach ($l in $entry.loaders) {
        switch ($l.ToLower()) {
            'neoforge' { $wanted.Add('NeoForge') }
            'fabric'   { $wanted.Add('Fabric') }
            'forge'    { $wanted.Add('Forge') }
        }
    }
    # CurseForge rejects uploads with nothing from the environment group,
    # so "both" or an unset environment tags Client and Server.
    if ($entry.environment -eq 'client') { $wanted.Add('Client') }
    elseif ($entry.environment -eq 'server') { $wanted.Add('Server') }
    else { $wanted.Add('Client'); $wanted.Add('Server') }
    if ($entry.javaVersion) { $wanted.Add($entry.javaVersion) }

    $ids = New-Object System.Collections.Generic.List[int]
    foreach ($name in $wanted) {
        $match = $script:cfVersions | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if ($match) { $ids.Add($match.id) } else { Write-Warning "  CurseForge has no version named '$name', leaving it off" }
    }
    return $ids
}

function Publish-Modrinth($entry, $jar) {
    $projectId = Resolve-ModrinthId $spec.modrinth.projectId
    if (-not $projectId) { return }
    $deps = @()
    foreach ($slug in @($entry.dependencies.modrinth)) {
        if (-not $slug) { continue }
        $depId = Resolve-ModrinthId $slug
        if ($depId) { $deps += @{ project_id = $depId; dependency_type = "required" } }
    }
    $versionNumber = "$($spec.version)+$($entry.target)"
    $data = @{
        project_id     = $projectId
        file_parts     = @("file")
        version_number = $versionNumber
        name           = "$($spec.name) $($spec.version)"
        changelog      = $changelog
        dependencies   = $deps
        game_versions  = @($entry.gameVersions)
        version_type   = $spec.releaseType
        loaders        = @($entry.loaders)
        featured       = $false
        primary_file   = "file"
    } | ConvertTo-Json -Depth 5

    if ($DryRun) {
        Write-Output "  Modrinth: would upload as $versionNumber [$($entry.loaders -join ',')] [$($entry.gameVersions -join ',')]"
        return
    }
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $content.Add([System.Net.Http.StringContent]::new($data, [Text.Encoding]::UTF8, "application/json"), "data")
    $bytes = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($jar.FullName))
    $bytes.Headers.ContentType = "application/java-archive"
    $content.Add($bytes, "file", $jar.Name)
    $req = [System.Net.Http.HttpRequestMessage]::new('POST', "https://api.modrinth.com/v2/version")
    $req.Headers.Add("Authorization", $modrinthToken)
    $req.Content = $content
    $resp = $http.SendAsync($req).Result
    $body = $resp.Content.ReadAsStringAsync().Result
    if ($resp.IsSuccessStatusCode) {
        Write-Output "  Modrinth: uploaded $versionNumber"
    } else {
        Write-Warning "  Modrinth upload failed ($($resp.StatusCode)): $body"
    }
}

function Publish-Curseforge($entry, $jar) {
    $ids = Get-CurseforgeVersionIds $entry
    $loaderLabel = ($entry.loaders | ForEach-Object { $_.Substring(0,1).ToUpper() + $_.Substring(1) }) -join '/'
    $displayName = "[$loaderLabel $($entry.gameVersions[0])] $($spec.name) v$($spec.version)"
    $metadata = @{
        changelog     = $changelog
        changelogType = "markdown"
        displayName   = $displayName
        gameVersions  = @($ids)
        releaseType   = $spec.releaseType
    }
    $cfDeps = @($entry.dependencies.curseforge) | Where-Object { $_ }
    if ($cfDeps.Count -gt 0) {
        $metadata.relations = @{ projects = @($cfDeps | ForEach-Object { @{ slug = $_; type = "requiredDependency" } }) }
    }
    $metadataJson = $metadata | ConvertTo-Json -Depth 5

    if ($DryRun) {
        Write-Output "  CurseForge: would upload as '$displayName' (version ids: $($ids -join ','))"
        return
    }
    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $content.Add([System.Net.Http.StringContent]::new($metadataJson, [Text.Encoding]::UTF8, "application/json"), "metadata")
    $bytes = [System.Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes($jar.FullName))
    $bytes.Headers.ContentType = "application/java-archive"
    $content.Add($bytes, "file", $jar.Name)
    $url = "https://minecraft.curseforge.com/api/projects/$($spec.curseforge.projectId)/upload-file"
    $req = [System.Net.Http.HttpRequestMessage]::new('POST', $url)
    $req.Headers.Add("X-Api-Token", $curseforgeToken)
    $req.Content = $content
    $resp = $http.SendAsync($req).Result
    $body = $resp.Content.ReadAsStringAsync().Result
    if ($resp.IsSuccessStatusCode) {
        Write-Output "  CurseForge: uploaded '$displayName' (file id $(($body | ConvertFrom-Json).id))"
    } else {
        Write-Warning "  CurseForge upload failed ($($resp.StatusCode)): $body"
    }
}

Write-Output "$($spec.name) $($spec.version) ($($spec.releaseType))"
foreach ($entry in $spec.files) {
    $jar = Get-ChildItem (Join-Path $root $entry.jar) -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    Write-Output ""
    if (-not $jar) {
        Write-Warning "No jar matches '$($entry.jar)', run the build for that target first."
        continue
    }
    Write-Output "$($jar.Name)"
    if (-not $SkipModrinth) { Publish-Modrinth $entry $jar }
    if (-not $SkipCurseforge) { Publish-Curseforge $entry $jar }
}
$http.Dispose()
Write-Output ""
Write-Output "Done."
