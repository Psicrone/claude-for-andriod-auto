# Installs the Claude Auto bridge on Windows, sets it to start when you sign in, and shows a
# QR code to pair your phone. In PowerShell:
#
#   irm https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/install.ps1 | iex
#
# No admin rights needed: everything goes in %LOCALAPPDATA%\ClaudeAuto. Run it again to update.
# Options (environment): CLAUDE_AUTO_NO_SERVICE=1 skips start-at-sign-in, CLAUDE_AUTO_NO_PAIR=1
# skips pairing at the end;
# CLAUDE_AUTO_PACKAGE=<file.tgz> installs that bridge package instead of downloading one.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Repo = 'Psicrone/claude-for-andriod-auto'
$NodeVersion = '22.20.0'
$HomeDir = if ($env:CLAUDE_AUTO_HOME) { $env:CLAUDE_AUTO_HOME } else { Join-Path $env:LOCALAPPDATA 'ClaudeAuto' }
$AppDir = Join-Path $HomeDir 'app'
$BinDir = Join-Path $HomeDir 'bin'

function Info($text) { Write-Host "==> $text" -ForegroundColor DarkYellow }

$arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'x64' }
    'ARM64' { 'arm64' }
    default { throw "Unsupported processor $env:PROCESSOR_ARCHITECTURE. Claude Code needs 64-bit Windows." }
}
New-Item -ItemType Directory -Force -Path $HomeDir, $BinDir | Out-Null

# --- Node.js 20 or newer: the one you have, or a private copy that only the bridge uses.
function Test-Node($exe) {
    if (-not $exe -or -not (Test-Path $exe)) { return $false }
    try { return [int](& $exe -p "process.versions.node.split('.')[0]") -ge 20 } catch { return $false }
}
$systemNode = (Get-Command node -ErrorAction SilentlyContinue).Source
if (Test-Node $systemNode) {
    $Node = $systemNode
    $Npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
} else {
    $NodeDir = Join-Path $HomeDir 'node'
    if (-not (Test-Node (Join-Path $NodeDir 'node.exe'))) {
        Info "Downloading Node.js $NodeVersion (used only by the bridge)"
        $zip = Join-Path $env:TEMP "node-$NodeVersion.zip"
        Invoke-WebRequest -UseBasicParsing "https://nodejs.org/dist/v$NodeVersion/node-v$NodeVersion-win-$arch.zip" -OutFile $zip
        $unpack = Join-Path $env:TEMP "node-$NodeVersion"
        Remove-Item -Recurse -Force $unpack, $NodeDir -ErrorAction SilentlyContinue
        Expand-Archive $zip -DestinationPath $unpack
        Move-Item (Join-Path $unpack "node-v$NodeVersion-win-$arch") $NodeDir
        Remove-Item -Recurse -Force $zip, $unpack
    }
    $Node = Join-Path $NodeDir 'node.exe'
    $Npm = Join-Path $NodeDir 'npm.cmd'
    $env:Path = "$NodeDir;$env:Path"
}

# --- The bridge: the latest release, or built from source if there's no release yet.
$work = Join-Path $env:TEMP ("claude-auto-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    Info 'Downloading the bridge'
    if ($env:CLAUDE_AUTO_PACKAGE) {
        $package = $env:CLAUDE_AUTO_PACKAGE
    } else {
        $package = Join-Path $work 'claude-auto-bridge.tgz'
        try {
            Invoke-WebRequest -UseBasicParsing "https://github.com/$Repo/releases/latest/download/claude-auto-bridge.tgz" -OutFile $package
        } catch {
            Info 'No release yet, building from the main branch'
            $src = Join-Path $work 'src.zip'
            Invoke-WebRequest -UseBasicParsing "https://codeload.github.com/$Repo/zip/refs/heads/main" -OutFile $src
            Expand-Archive $src -DestinationPath $work
            $bridgeSrc = Join-Path (Get-ChildItem $work -Directory | Select-Object -First 1).FullName 'bridge'
            Push-Location $bridgeSrc
            try {
                & $Npm ci --no-audit --no-fund --loglevel=error
                & $Npm run build --silent
                & $Npm pack --silent | Out-Null
                $package = (Get-ChildItem 'claude-auto-bridge-*.tgz' | Select-Object -First 1).FullName
            } finally { Pop-Location }
        }
    }
    Info 'Installing'
    & $Npm install -g --prefix $AppDir --no-audit --no-fund --no-update-notifier --loglevel=error $package | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'npm install failed.' }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

# A launcher that always uses the Node above, on your PATH.
$script = Join-Path $AppDir 'node_modules\claude-auto-bridge\dist\server.js'
$launcher = Join-Path $BinDir 'claude-auto-bridge.cmd'
Set-Content -Path $launcher -Encoding ASCII -Value "@echo off`r`n`"$Node`" `"$script`" %*"
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (-not ($userPath -split ';' | Where-Object { $_ -eq $BinDir })) {
    [Environment]::SetEnvironmentVariable('Path', ($(if ($userPath) { "$userPath;" } else { '' }) + $BinDir), 'User')
}
$env:Path = "$BinDir;$env:Path"

# --- Questions (project folders, permissions, Claude sign-in).
& $launcher setup

if ($env:CLAUDE_AUTO_NO_SERVICE -ne '1') {
    Info 'Starting the bridge, now and whenever you sign in'
    & $launcher service install
    if ($LASTEXITCODE -eq 0) {
        for ($i = 0; $i -lt 20; $i++) {
            if ((& $launcher service status) -match 'Running') { break }
            Start-Sleep -Milliseconds 500
        }
    } else {
        Info "Couldn't set up start-at-sign-in. Run claude-auto-bridge yourself when you need it."
    }
}

Write-Host ''
Info 'Installed. Commands (open a new terminal if claude-auto-bridge is not found):'
Write-Host '  claude-auto-bridge pair      pair another phone'
Write-Host '  claude-auto-bridge setup     change folders or permissions'
Write-Host '  claude-auto-bridge devices   list or revoke paired phones'
Write-Host ''
if ($env:CLAUDE_AUTO_NO_PAIR -ne '1') {
    Info 'Open Claude Auto on your phone, choose My computer, and scan this:'
    & $launcher pair
}
