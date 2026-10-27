# Builds a portable Windows release: dist\DiamondRushRecomp\DiamondRushRecomp.exe (+ bundled Java runtime)
# and zips it to dist\DiamondRushRecomp-<version>-windows-x64.zip
param([string]$Version = '0.1.0')
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'build.ps1')

$jdk = Join-Path $root 'tools\jdk\bin'
$jpackage = if (Test-Path (Join-Path $jdk 'jpackage.exe')) { Join-Path $jdk 'jpackage.exe' } else { 'jpackage' }

$dist = Join-Path $root 'dist'
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
$pkgInput = Join-Path $root 'build\pkg'
if (Test-Path $pkgInput) { Remove-Item -Recurse -Force $pkgInput }
New-Item -ItemType Directory -Force $pkgInput | Out-Null
Copy-Item (Join-Path $root 'build\diamondrush-recomp.jar') $pkgInput

& $jpackage --type app-image `
    --name DiamondRushRecomp `
    --app-version $Version `
    --vendor 'DiamondRushRecomp' `
    --input $pkgInput `
    --main-jar diamondrush-recomp.jar `
    --main-class drpc.Launcher `
    --add-modules 'java.base,java.desktop,jdk.unsupported' `
    --jlink-options '--strip-debug --no-header-files --no-man-pages --compress=zip-6' `
    --dest $dist
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

Copy-Item (Join-Path $root 'LEIA-ME.txt') (Join-Path $dist 'DiamondRushRecomp')
$zip = Join-Path $dist "DiamondRushRecomp-$Version-windows-x64.zip"
Compress-Archive -Path (Join-Path $dist 'DiamondRushRecomp') -DestinationPath $zip
Write-Host "OK -> $zip"
