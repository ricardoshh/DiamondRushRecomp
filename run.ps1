# Runs the port. Pass the path of the original game .jar, or leave empty to auto-detect it in this folder.
param([string]$GameJar = '')
$root = $PSScriptRoot
$java = Join-Path $root 'tools\jdk\bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

Push-Location $root
try {
    $jvmArgs = @('-Dsun.java2d.uiScale=1', '-jar', 'build\diamondrush-pc.jar')
    if ($GameJar) { $jvmArgs += $GameJar }
    & $java @jvmArgs
} finally {
    Pop-Location
}
