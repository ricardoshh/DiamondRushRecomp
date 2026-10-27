# Compiles the launcher + javax.microedition layer into build\diamondrush-recomp.jar
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Join-Path $root 'tools\jdk\bin'
if (-not (Test-Path (Join-Path $jdk 'javac.exe'))) { $jdk = '' }  # fall back to JDK on PATH / JAVA_HOME

$javac = if ($jdk) { Join-Path $jdk 'javac.exe' } else { 'javac' }
$jarTool = if ($jdk) { Join-Path $jdk 'jar.exe' } else { 'jar' }

# Download dependencies listed in deps.txt
$lib = Join-Path $root 'lib'
New-Item -ItemType Directory -Force $lib | Out-Null
$libs = @()
foreach ($line in Get-Content (Join-Path $root 'deps.txt')) {
    $line = $line.Trim()
    if (-not $line -or $line.StartsWith('#')) { continue }
    $dest = Join-Path $lib (Split-Path $line -Leaf)
    if (-not (Test-Path $dest)) {
        Write-Host "download $line"
        Invoke-WebRequest -UseBasicParsing -Uri "https://repo1.maven.org/maven2/$line" -OutFile $dest
    }
    $libs += $dest
}

$out = Join-Path $root 'build'
$classes = Join-Path $out 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src') | ForEach-Object { $_.FullName }
& $javac --release 21 -encoding UTF-8 -Xlint:-options -cp ($libs -join ';') -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# Bundle the libraries (and their native binaries) into the output so the launcher is a single jar.
Push-Location $classes
try {
    foreach ($l in $libs) {
        & $jarTool --extract --file $l
        if ($LASTEXITCODE -ne 0) { throw "extract $l failed" }
    }
    Remove-Item -Recurse -Force 'META-INF' -ErrorAction SilentlyContinue
} finally {
    Pop-Location
}

& $jarTool --create --file (Join-Path $out 'diamondrush-recomp.jar') --main-class drpc.Launcher -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Write-Host "OK -> build\diamondrush-recomp.jar"
