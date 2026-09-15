param(
    [string]$DecodedDir = "work/base-decoded",
    [string]$AndroidJar = "tools/android-sdk/platform-35/android-35/android.jar",
    [string]$BuildTools = "tools/android-sdk/build-tools-35/android-15",
    [string]$OutputDir = "work/weather",
    [switch]$BuildDeviceTests
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$decoded = (Resolve-Path -LiteralPath $DecodedDir).Path
if (-not (Select-String -LiteralPath (Join-Path $decoded 'apktool.yml') -Pattern 'versionCode: 55' -Quiet)) {
    throw 'Weather bridge supports HiFuture Fit 1.5.1.5 (version code 55) only.'
}
$android = (Resolve-Path -LiteralPath $AndroidJar).Path
$bt = (Resolve-Path -LiteralPath $BuildTools).Path
New-Item -ItemType Directory -Force $OutputDir | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDir).Path

function Run([string]$Command, [string[]]$Arguments) {
    & $Command @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Command failed ($LASTEXITCODE)" }
}
function Build-JavaApk([string]$Name, [string[]]$Sources, [string]$ManifestPath) {
    $directory = Join-Path $output $Name
    New-Item -ItemType Directory -Force "$directory/classes", "$directory/dex" | Out-Null
    Run 'javac' (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $android, '-d', "$directory/classes") + $Sources)
    Run 'jar' @('cf', "$directory/input.jar", '-C', "$directory/classes", '.')
    Run 'java' @('-cp', "$bt/lib/d8.jar", 'com.android.tools.r8.D8', '--min-api', '26', '--lib', $android, '--output', "$directory/dex", "$directory/input.jar")
    Run "$bt/aapt.exe" @('package', '-f', '-M', $ManifestPath, '-I', $android, '-F', "$directory/unsigned.apk")
    Push-Location "$directory/dex"
    try { Run "$bt/aapt.exe" @('add', "$directory/unsigned.apk", 'classes.dex') } finally { Pop-Location }
    return "$directory/unsigned.apk"
}

$shared = @(Get-ChildItem weather/shared -Recurse -Filter '*.java' | ForEach-Object FullName)
$companion = @(Get-ChildItem weather/companion/src -Recurse -Filter '*.java' | ForEach-Object FullName)
$bridge = @(Get-ChildItem weather/bridge -Recurse -Filter '*.java' | ForEach-Object FullName)
$manifest = (Resolve-Path weather/companion/AndroidManifest.xml).Path
# The bridge's temporary APK exists only to let Apktool disassemble our own dex.
$null = Build-JavaApk 'companion' ($shared + $companion) $manifest
$null = Build-JavaApk 'bridge' ($shared + $bridge) $manifest
if ($BuildDeviceTests) {
    $null = Build-JavaApk 'tests' @((Resolve-Path 'weather/tests/BridgeInstrumentation.java').Path) (Resolve-Path 'weather/tests/AndroidManifest.xml').Path
}
Run 'java' @('-jar', 'tools/apktool_3.0.3.jar', 'd', '-f', '-r', "$output/bridge/unsigned.apk", '-o', "$output/bridge-decoded")
$target = Join-Path $decoded 'smali_classes9/org/hifuture/weather'
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item -Path "$output/bridge-decoded/smali/org/hifuture/weather/*.smali" -Destination $target -Force

# Apply only to the supported package/version after the offline privacy patch.
$manifestPath = Join-Path $decoded 'AndroidManifest.xml'
[xml]$doc = Get-Content -LiteralPath $manifestPath -Raw
$ns = 'http://schemas.android.com/apk/res/android'
if ($doc.manifest.package -ne 'com.cs.ute.hiFuture') { throw 'Unsupported package' }
if (@($doc.manifest.'uses-permission' | Where-Object { $_.GetAttribute('name', $ns) -eq 'android.permission.INTERNET' }).Count) {
    throw 'Apply the offline privacy patch first. HiFuture Fit must not have Internet permission.'
}
$permission = 'com.cs.ute.hiFuture.permission.PRIVATE_WEATHER'
if (-not @($doc.manifest.permission | Where-Object { $_.GetAttribute('name', $ns) -eq $permission }).Count) {
    $node = $doc.CreateElement('permission')
    [void]$node.SetAttribute('name', $ns, $permission)
    [void]$node.SetAttribute('protectionLevel', $ns, 'signature')
    [void]$doc.manifest.AppendChild($node)
}
if (-not @($doc.manifest.application.receiver | Where-Object { $_.GetAttribute('name', $ns) -eq 'org.hifuture.weather.PrivateWeatherReceiver' }).Count) {
    $node = $doc.CreateElement('receiver')
    [void]$node.SetAttribute('name', $ns, 'org.hifuture.weather.PrivateWeatherReceiver')
    [void]$node.SetAttribute('exported', $ns, 'true')
    [void]$node.SetAttribute('permission', $ns, $permission)
    $filter = $doc.CreateElement('intent-filter')
    $action = $doc.CreateElement('action')
    [void]$action.SetAttribute('name', $ns, 'org.hifuture.weather.UPDATE')
    [void]$filter.AppendChild($action)
    [void]$node.AppendChild($filter)
    [void]$doc.manifest.application.AppendChild($node)
}
$settings = [System.Xml.XmlWriterSettings]::new()
$settings.Encoding = [System.Text.UTF8Encoding]::new($false)
$writer = [System.Xml.XmlWriter]::Create($manifestPath, $settings)
try { $doc.Save($writer) } finally { $writer.Dispose() }

function Patch-Method([string]$Relative, [string]$Header, [string]$Body) {
    $path = Join-Path $decoded "smali_classes8/$Relative"
    $text = [System.IO.File]::ReadAllText($path)
    $start = $text.IndexOf($Header, [StringComparison]::Ordinal)
    if ($start -lt 0) { throw "Method missing: $Header" }
    $end = $text.IndexOf('.end method', $start, [StringComparison]::Ordinal) + '.end method'.Length
    $replacement = $Header + "`n" + $Body.TrimEnd() + "`n.end method"
    [System.IO.File]::WriteAllText($path, $text.Substring(0, $start) + $replacement + $text.Substring($end), [System.Text.UTF8Encoding]::new($false))
}
Patch-Method 'com/yc/gloryfitpro/ui/fragment/HomeFragment.smali' '.method private myStartLocation()V' @'
    .locals 0
    invoke-static {p0}, Lorg/hifuture/weather/PrivateWeatherReceiver;->refreshHome(Ljava/lang/Object;)V
    return-void
'@
Patch-Method 'com/yc/gloryfitpro/ui/activity/main/home/WeatherActivity.smali' '.method public init()V' @'
    .locals 0
    invoke-static {p0}, Lorg/hifuture/weather/PrivateWeatherReceiver;->openCompanion(Landroid/app/Activity;)V
    return-void
'@
# The home screen must also hide expired weather when resumed offline.
$homePath = Join-Path $decoded 'smali_classes8/com/yc/gloryfitpro/ui/fragment/HomeFragment.smali'
$weatherHomeText = [System.IO.File]::ReadAllText($homePath)
$uiHeader = '.method private updateWeatherUi(Lcom/yc/gloryfitpro/bean/FutureWeatherInfo;)V'
$uiStart = $weatherHomeText.IndexOf($uiHeader, [StringComparison]::Ordinal)
if ($uiStart -lt 0) { throw 'Home weather UI method missing' }
$uiEnd = $weatherHomeText.IndexOf('.end method', $uiStart, [StringComparison]::Ordinal)
$ui = $weatherHomeText.Substring($uiStart, $uiEnd - $uiStart)
if (-not $ui.Contains(':private_weather_ui_fresh')) {
    $locals = [regex]::Match($ui, '\.locals \d+\r?\n')
    if (-not $locals.Success) { throw 'Home weather UI locals missing' }
    $position = $uiStart + $locals.Index + $locals.Length
    $guard = @'

    invoke-static {}, Lorg/hifuture/weather/PrivateWeatherReceiver;->isFresh()Z
    move-result v0
    if-nez v0, :private_weather_ui_fresh
    new-instance p1, Lcom/yc/gloryfitpro/bean/FutureWeatherInfo;
    invoke-direct {p1}, Lcom/yc/gloryfitpro/bean/FutureWeatherInfo;-><init>()V
    :private_weather_ui_fresh

'@
    [System.IO.File]::WriteAllText($homePath, $weatherHomeText.Insert($position, $guard + "`n"), [System.Text.UTF8Encoding]::new($false))
}
# Every existing reconnect/unit-change/weather sync must honor opt-out and expiry.
$modelPath = Join-Path $decoded 'smali_classes8/com/yc/gloryfitpro/model/main/MainHomeModelRkImpl.smali'
$text = [System.IO.File]::ReadAllText($modelPath)
$header = '.method public mySetFutureWeatherToBle(Lcom/yc/gloryfitpro/bean/FutureWeatherInfo;)Lcom/yc/nadalsdk/bean/Response;'
$start = $text.IndexOf($header, [StringComparison]::Ordinal)
if ($start -lt 0) { throw 'Weather sync method missing' }
$end = $text.IndexOf('.end method', $start, [StringComparison]::Ordinal)
$method = $text.Substring($start, $end - $start)
if (-not $method.Contains('Lorg/hifuture/weather/PrivateWeatherReceiver;->isFresh()Z')) {
    $locals = [regex]::Match($method, '\.locals \d+\r?\n')
    if (-not $locals.Success) { throw 'Weather sync locals missing' }
    $position = $start + $locals.Index + $locals.Length
    $guard = @'

    invoke-static {}, Lorg/hifuture/weather/PrivateWeatherReceiver;->isFresh()Z
    move-result v0
    if-nez v0, :private_weather_fresh
    new-instance v0, Lcom/yc/nadalsdk/bean/Response;
    const v1, 0x186a0
    invoke-direct {v0, v1}, Lcom/yc/nadalsdk/bean/Response;-><init>(I)V
    return-object v0
    :private_weather_fresh

'@
    [System.IO.File]::WriteAllText($modelPath, $text.Insert($position, $guard + "`n"), [System.Text.UTF8Encoding]::new($false))
}
Write-Output "Weather bridge installed in $decoded"
Write-Output "Sign $output/companion/unsigned.apk with the SAME private key as HiFuture Fit."
