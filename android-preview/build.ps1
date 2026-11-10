param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildTools
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$AndroidJar = (Resolve-Path -LiteralPath $AndroidJar).Path
$BuildTools = (Resolve-Path -LiteralPath $BuildTools).Path
$project = (Get-Item -LiteralPath $PSScriptRoot).FullName
$build = Join-Path $project '.build'
$stage = Join-Path $build ([Guid]::NewGuid().ToString('N'))
$classes = Join-Path $stage 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null

function Invoke-BuildTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool exited with code $LASTEXITCODE" }
}

$manifest = Join-Path $project 'AndroidManifest.xml'
$unsigned = Join-Path $stage 'unsigned.apk'
$aligned = Join-Path $stage 'aligned.apk'
$dex = Join-Path $stage 'classes.dex'
$compiled = Join-Path $stage 'classes.jar'
$resources = Join-Path $stage 'resources.zip'
$generated = Join-Path $stage 'generated'
$signed = Join-Path $stage 'signed.apk'
$keystore = Join-Path $build 'preview-debug.jks'
$output = Join-Path $project 'earclean-preview.apk'
New-Item -ItemType Directory -Path $generated -Force | Out-Null
$aapt = Join-Path $BuildTools 'aapt2.exe'
$relativeStage = Resolve-Path -Relative -LiteralPath $stage
Invoke-BuildTool $aapt @(
    'compile', '--dir', (Resolve-Path -Relative -LiteralPath (Join-Path $project 'res')),
    '-o', (Join-Path $relativeStage 'resources.zip')
)
Invoke-BuildTool $aapt @(
    'link', '-I', (Resolve-Path -Relative -LiteralPath $AndroidJar),
    '--manifest', (Resolve-Path -Relative -LiteralPath $manifest),
    '--java', (Join-Path $relativeStage 'generated'),
    '-o', (Join-Path $relativeStage 'unsigned.apk'),
    (Resolve-Path -Relative -LiteralPath $resources)
)
$sources = @(Get-ChildItem -LiteralPath @((Join-Path $project 'src'), $generated) -Filter '*.java' -Recurse -File |
    ForEach-Object FullName)

Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin/javac.exe') (
    @('-encoding', 'UTF-8', '--release', '8', '-classpath', $AndroidJar, '-d', $classes) + $sources
)
Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin/jar.exe') @('cf', $compiled, '-C', $classes, '.')
Invoke-BuildTool (Join-Path $BuildTools 'd8.bat') @(
    '--lib', $AndroidJar, '--min-api', '23', '--output', $stage, $compiled
)
Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin/jar.exe') @('uf', $unsigned, '-C', $stage, 'classes.dex')
Invoke-BuildTool (Join-Path $BuildTools 'zipalign.exe') @(
    '-f', '4', (Resolve-Path -Relative -LiteralPath $unsigned),
    (Join-Path (Resolve-Path -Relative -LiteralPath $stage) 'aligned.apk')
)
if (!(Test-Path -LiteralPath $keystore)) {
    Invoke-BuildTool (Join-Path $env:JAVA_HOME 'bin/keytool.exe') @(
        '-genkeypair', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=Earclean Preview Debug', '-noprompt'
    )
}
$signer = Join-Path $BuildTools 'apksigner.bat'
Invoke-BuildTool $signer @(
    'sign', '--ks', $keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', $signed, $aligned
)
Invoke-BuildTool $signer @('verify', '--verbose', $signed)
Copy-Item -LiteralPath $signed -Destination $output -Force
Write-Output $output
