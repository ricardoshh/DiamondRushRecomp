# Builds a portable Windows release: dist\DiamondRushRecomp\DiamondRushRecomp.exe (+ bundled Java runtime)
# and zips it to dist\DiamondRushRecomp-<version>-windows-x64.zip
#
# -GameJar <path>  PERSONAL build: embeds your own copy of the game inside the app, so the exe runs
#                  without asking for the file. Never publish that zip: it contains Gameloft's game.
param([string]$Version = '0.1.0', [string]$GameJar = '')
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
if ($GameJar) {
    # Stored as game.bin, not .jar: jpackage puts every input .jar on the classpath, which
    # would load the game classes without our bytecode patches.
    Copy-Item $GameJar (Join-Path $pkgInput 'game.bin')
}

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
$suffix = if ($GameJar) { '-PESSOAL-nao-distribuir' } else { '' }
$zip = Join-Path $dist "DiamondRushRecomp-$Version-windows-x64$suffix.zip"
Compress-Archive -Path (Join-Path $dist 'DiamondRushRecomp') -DestinationPath $zip
Write-Host "OK -> $zip"
if ($GameJar) { Write-Warning 'Build pessoal com o jogo embutido: NAO publique este zip (contem o jogo da Gameloft).' }
