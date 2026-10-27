# Compiles the launcher + javax.microedition layer into build\diamondrush-pc.jar
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Join-Path $root 'tools\jdk\bin'
if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) { $jdk = '' }  # fall back to JDK on PATH

$javac = if ($jdk) { Join-Path $jdk 'javac.exe' } else { 'javac' }
$jarTool = if ($jdk) { Join-Path $jdk 'jar.exe' } else { 'jar' }

$out = Join-Path $root 'build'
$classes = Join-Path $out 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null

$libs = Get-ChildItem (Join-Path $root 'lib') -Filter *.jar | ForEach-Object { $_.FullName }
$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src') | ForEach-Object { $_.FullName }
& $javac --release 21 -encoding UTF-8 -Xlint:-options -cp ($libs -join ';') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# Bundle the libraries into the output so the launcher is a single jar.
Push-Location $classes
try {
    foreach ($lib in $libs) {
        & $jarTool --extract --file $lib
        if ($LASTEXITCODE -ne 0) { throw "extract $lib failed" }
    }
    Remove-Item -Recurse -Force 'META-INF' -ErrorAction SilentlyContinue
} finally {
    Pop-Location
}

& $jarTool --create --file (Join-Path $out 'diamondrush-pc.jar') --main-class drpc.Launcher -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "OK -> build\diamondrush-pc.jar"
