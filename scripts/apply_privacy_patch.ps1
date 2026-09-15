param(
    [string]$DecodedDir = "work/base-decoded"
)

$ErrorActionPreference = "Stop"
$decoded = (Resolve-Path -LiteralPath $DecodedDir).Path
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, $utf8NoBom)
}

function Set-SmaliMethod([string]$Path, [string]$Header, [string]$Body) {
    $text = [System.IO.File]::ReadAllText($Path)
    $start = $text.IndexOf($Header, [System.StringComparison]::Ordinal)
    if ($start -lt 0) {
        throw "Smali method not found: $Header in $Path"
    }
    $endMarker = ".end method"
    $end = $text.IndexOf($endMarker, $start, [System.StringComparison]::Ordinal)
    if ($end -lt 0) {
        throw "Smali method end not found: $Header in $Path"
    }
    $end += $endMarker.Length
    $replacement = $Header + "`r`n" + $Body.TrimEnd() + "`r`n.end method"
    Write-Utf8NoBom $Path ($text.Substring(0, $start) + $replacement + $text.Substring($end))
}

function Disable-PublicStaticVoidMethods([string]$Path) {
    $text = [System.IO.File]::ReadAllText($Path)
    $pattern = '(?ms)^(?<header>\.method public static [^\r\n]+\)V)\r?\n.*?^\.end method'
    $replacement = '${header}' + "`r`n    .locals 0`r`n`r`n    return-void`r`n.end method"
    $patched = [System.Text.RegularExpressions.Regex]::Replace($text, $pattern, $replacement)
    if (-not [System.Text.RegularExpressions.Regex]::IsMatch($text, $pattern)) {
        throw "No public static void methods found in $Path"
    }
    Write-Utf8NoBom $Path $patched
}

function Find-One([string]$LeafName) {
    $matches = @(Get-ChildItem -Path $decoded -Recurse -File -Filter $LeafName)
    if ($matches.Count -ne 1) {
        throw "Expected one $LeafName under $decoded, found $($matches.Count)"
    }
    return $matches[0].FullName
}

function Find-OneEnding([string]$RelativeSuffix) {
    $suffix = $RelativeSuffix.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $matches = @(Get-ChildItem -Path $decoded -Recurse -File | Where-Object {
        $_.FullName.EndsWith($suffix, [System.StringComparison]::OrdinalIgnoreCase)
    })
    if ($matches.Count -ne 1) {
        throw "Expected one path ending in $RelativeSuffix under $decoded, found $($matches.Count)"
    }
    return $matches[0].FullName
}

# Manifest: retain local Bluetooth/notification/communications capabilities, but
# remove all general Internet egress, identifiers, remote health export, and
# third-party package discovery/auto-start surfaces.
$manifestPath = Join-Path $decoded "AndroidManifest.xml"
$doc = [System.Xml.XmlDocument]::new()
$doc.PreserveWhitespace = $true
$doc.Load($manifestPath)
$androidNs = "http://schemas.android.com/apk/res/android"
$manifest = $doc.DocumentElement
$application = $doc.SelectSingleNode('/manifest/application')

$permissionsToRemove = @(
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.ACCESS_WIFI_STATE',
    'android.permission.CHANGE_WIFI_STATE',
    'android.permission.RECORD_AUDIO',
    'android.permission.BLUETOOTH_PRIVILEGED',
    'android.permission.health.WRITE_HEART_RATE',
    'android.permission.health.WRITE_STEPS',
    'android.permission.health.WRITE_DISTANCE',
    'android.permission.health.WRITE_TOTAL_CALORIES_BURNED',
    'android.permission.health.WRITE_SLEEP',
    'android.permission.health.WRITE_BLOOD_PRESSURE',
    'android.permission.health.WRITE_OXYGEN_SATURATION',
    'android.permission.health.WRITE_BODY_TEMPERATURE',
    'android.permission.WRITE_SETTINGS',
    'android.permission.CHANGE_CONFIGURATION',
    'com.autonavi.minimap.permission.NAVIGATION_SERVICE',
    'com.google.android.gms.permission.AD_ID',
    'com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE'
)
foreach ($node in @($doc.SelectNodes('/manifest/uses-permission'))) {
    if ($permissionsToRemove -contains $node.GetAttribute('name', $androidNs)) {
        [void]$manifest.RemoveChild($node)
    }
}
foreach ($node in @($doc.SelectNodes('/manifest/queries'))) {
    [void]$manifest.RemoveChild($node)
}

$componentNamesToRemove = @(
    'com.yc.gloryfitpro.wxapi.WXEntryActivity',
    'com.yc.gloryfitpro.wxapi.WXPayEntryActivity',
    'com.yc.gloryfitpro.ui.activity.main.mine.OAuthRedirectActivity',
    'com.yc.gloryfitpro.services.sport.AdmapLocationService',
    'com.yc.gloryfitpro.services.sport.AmapLocationGpsService',
    'com.yc.gloryfitpro.services.sport.GoogleLocationService',
    'com.yc.gloryfitpro.services.sport.GoogleLocationGpsService',
    'com.amap.api.location.APSService',
    'androidx.health.platform.client.impl.sdkservice.HealthDataSdkService',
    'com.facebook.FacebookActivity',
    'com.facebook.CustomTabActivity',
    'com.facebook.CustomTabMainActivity',
    'com.facebook.FacebookContentProvider',
    'com.facebook.internal.FacebookInitProvider',
    'com.facebook.CurrentAccessTokenExpirationBroadcastReceiver',
    'com.facebook.AuthenticationTokenManager$CurrentAuthenticationTokenChangedBroadcastReceiver',
    'com.tencent.tauth.AuthActivity',
    'com.tencent.connect.common.AssistActivity',
    'com.google.android.gms.auth.api.signin.internal.SignInHubActivity',
    'com.google.android.gms.auth.api.signin.RevocationBoundService',
    'com.google.android.gms.common.api.GoogleApiActivity',
    'com.twitter.sdk.android.tweetui.PlayerActivity',
    'com.twitter.sdk.android.tweetui.GalleryActivity',
    'com.twitter.sdk.android.tweetcomposer.ComposerActivity',
    'com.twitter.sdk.android.tweetcomposer.TweetUploadService',
    'com.twitter.sdk.android.core.identity.OAuthActivity',
    'com.tenmeter.smlibrary.activity.SMGameH5Activity',
    'com.tenmeter.smlibrary.activity.SMGameListActivity',
    'com.tenmeter.smlibrary.activity.SMGameListNewActivity',
    'com.tenmeter.smlibrary.activity.SMGameListSubActivity',
    'com.tenmeter.smlibrary.activity.SMVipGameListActivity',
    'com.tenmeter.smlibrary.activity.SMWebViewActivity',
    'com.tenmeter.smlibrary.activity.SMADActivity',
    'com.tenmeter.smlibrary.activity.SMGameListH5Activity',
    'com.tenmeter.smlibrary.provider.BaseProvider',
    'com.microsoft.cognitiveservices.speech.util.InternalContentProvider',
    'com.alipay.sdk.app.H5PayActivity',
    'com.alipay.sdk.app.H5AuthActivity',
    'com.alipay.sdk.app.PayResultActivity',
    'com.alipay.sdk.app.AlipayResultActivity',
    'com.alipay.sdk.app.H5OpenAuthActivity',
    'com.alipay.sdk.app.APayEntranceActivity'
)
foreach ($kind in @('activity', 'activity-alias', 'service', 'receiver', 'provider')) {
    foreach ($node in @($application.SelectNodes($kind))) {
        if ($componentNamesToRemove -contains $node.GetAttribute('name', $androidNs)) {
            [void]$application.RemoveChild($node)
        }
    }
}

$metadataToRemove = @(
    'com.facebook.sdk.ApplicationId',
    'com.facebook.sdk.ClientToken',
    'com.facebook.sdk.AdvertiserIDCollectionEnabled',
    'com.facebook.sdk.AutoLogAppEventsEnabled',
    'com.amap.api.v2.apikey',
    'com.google.android.geo.API_KEY'
)
foreach ($node in @($application.SelectNodes('meta-data'))) {
    if ($metadataToRemove -contains $node.GetAttribute('name', $androidNs)) {
        [void]$application.RemoveChild($node)
    }
}

$startupProvider = $application.SelectNodes('provider') | Where-Object {
    $_.GetAttribute('name', $androidNs) -eq 'androidx.startup.InitializationProvider'
}
foreach ($node in @($startupProvider.SelectNodes('meta-data'))) {
    $name = $node.GetAttribute('name', $androidNs)
    if ($name -eq 'com.drake.net.internal.NetInitializer' -or $name -eq 'com.king.logx.initialize.LogXInitializer') {
        [void]$startupProvider.RemoveChild($node)
    }
}

$splash = $application.SelectNodes('activity') | Where-Object {
    $_.GetAttribute('name', $androidNs) -eq 'com.yc.gloryfitpro.ui.activity.SplashActivity'
}
foreach ($filter in @($splash.SelectNodes('intent-filter'))) {
    $hasUmengScheme = @($filter.SelectNodes('data')) | Where-Object {
        $_.GetAttribute('scheme', $androidNs) -like 'um.*'
    }
    if ($hasUmengScheme) {
        [void]$splash.RemoveChild($filter)
    }
}

$xmlSettings = [System.Xml.XmlWriterSettings]::new()
$xmlSettings.Encoding = $utf8NoBom
$xmlSettings.Indent = $false
$xmlSettings.NewLineChars = "`n"
$xmlSettings.NewLineHandling = [System.Xml.NewLineHandling]::Replace
$writer = [System.Xml.XmlWriter]::Create($manifestPath, $xmlSettings)
try {
    $doc.Save($writer)
} finally {
    $writer.Dispose()
}

# Hide the social-login strip. The built-in Skip button remains the supported
# entry to the local-only experience.
$loginLayoutPath = Join-Path $decoded 'res/layout/activity_login.xml'
$layoutDoc = [System.Xml.XmlDocument]::new()
$layoutDoc.PreserveWhitespace = $true
$layoutDoc.Load($loginLayoutPath)
$layoutNs = [System.Xml.XmlNamespaceManager]::new($layoutDoc.NameTable)
$layoutNs.AddNamespace('android', $androidNs)
$loginList = $layoutDoc.SelectSingleNode('//*[@android:id="@id/cl_login_list"]', $layoutNs)
if ($null -eq $loginList) {
    throw 'Login social-login container not found'
}
[void]$loginList.SetAttribute('visibility', $androidNs, 'gone')
$writer = [System.Xml.XmlWriter]::Create($loginLayoutPath, $xmlSettings)
try {
    $layoutDoc.Save($writer)
} finally {
    $writer.Dispose()
}

# Keep only local application/bootstrap initialization. Do not initialize
# Facebook, Umeng, Amap, remote AI, game services, or persistent debug logs.
$appSmali = Find-OneEnding 'com/yc/gloryfitpro/MyApplication.smali'
$appOnCreate = @'
    .locals 1

    invoke-super {p0}, Landroidx/multidex/MultiDexApplication;->onCreate()V

    invoke-static {p0}, Landroidx/multidex/MultiDex;->install(Landroid/content/Context;)V

    sput-object p0, Lcom/yc/gloryfitpro/MyApplication;->myApplication:Lcom/yc/gloryfitpro/MyApplication;

    invoke-virtual {p0}, Lcom/yc/gloryfitpro/MyApplication;->getApplicationContext()Landroid/content/Context;
    move-result-object v0
    sput-object v0, Lcom/yc/gloryfitpro/MyApplication;->mContext:Landroid/content/Context;

    invoke-static {p0}, Lcom/tencent/mmkv/MMKV;->initialize(Landroid/content/Context;)Ljava/lang/String;
    move-result-object v0

    new-instance v0, Lcom/yc/gloryfitpro/net/http/HttpModule;
    invoke-direct {v0}, Lcom/yc/gloryfitpro/net/http/HttpModule;-><init>()V
    iput-object v0, p0, Lcom/yc/gloryfitpro/MyApplication;->httpModule:Lcom/yc/gloryfitpro/net/http/HttpModule;

    invoke-static {}, Lcom/yc/gloryfitpro/dao/GreenDaoHelper;->getInstance()Lcom/yc/gloryfitpro/dao/GreenDaoHelper;
    move-result-object v0

    sget-object v0, Lcom/yc/gloryfitpro/MyApplication;->mContext:Landroid/content/Context;
    invoke-static {v0}, Lcom/yc/gloryfitpro/base/NadalSdkComponentImp;->getInstance(Landroid/content/Context;)Lcom/yc/gloryfitpro/base/NadalSdkComponentImp;
    move-result-object v0

    sget-object v0, Lcom/yc/gloryfitpro/MyApplication;->mContext:Landroid/content/Context;
    invoke-static {v0}, Lcom/yc/gloryfitpro/base/UteSdkComponentImp;->getInstance(Landroid/content/Context;)Lcom/yc/gloryfitpro/base/UteSdkComponentImp;
    move-result-object v0

    invoke-direct {p0}, Lcom/yc/gloryfitpro/MyApplication;->registerActivityLifecycleCallbacks()V
    invoke-direct {p0}, Lcom/yc/gloryfitpro/MyApplication;->increaseCursorWindowSize()V

    return-void
'@
Set-SmaliMethod $appSmali '.method public onCreate()V' $appOnCreate
Set-SmaliMethod $appSmali '.method private initThirdPartyConfig()V' "    .locals 0`r`n`r`n    return-void"

$loginSmali = Find-OneEnding 'com/yc/gloryfitpro/ui/activity/login/LoginActivity.smali'
Set-SmaliMethod $loginSmali '.method private initThirdPartyConfig()V' "    .locals 0`r`n`r`n    return-void"

$loginOtherSmali = Find-OneEnding 'com/yc/gloryfitpro/ui/activity/login/LoginOtherActivity.smali'
Set-SmaliMethod $loginOtherSmali '.method protected initOtherLoginConfig()V' "    .locals 0`r`n`r`n    return-void"

$networkSmali = Find-OneEnding 'com/yc/gloryfitpro/utils/NetworkUtils.smali'
Set-SmaliMethod $networkSmali '.method public isNetworkAvailable()Z' @'
    .locals 1

    const/4 v0, 0x0
    return v0
'@

# A fresh local/Skip profile reaches MainActivity before the vendor flow has
# requested Android 12+'s Nearby Devices permission. Starting MainService as a
# connected-device foreground service in that state causes a SecurityException
# and a restart/crash loop. Request only Bluetooth scan/connect here and defer
# the service until both are granted; declining leaves the UI usable offline.
$mainActivitySmali = Find-OneEnding 'com/yc/gloryfitpro/ui/activity/main/MainActivity.smali'
Set-SmaliMethod $mainActivitySmali '.method private startMainService()V' @'
    .locals 4

    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v1, 0x1f
    if-lt v0, v1, :start_service

    const-string v0, "android.permission.BLUETOOTH_CONNECT"
    invoke-static {p0, v0}, Landroidx/core/app/ActivityCompat;->checkSelfPermission(Landroid/content/Context;Ljava/lang/String;)I
    move-result v0
    if-eqz v0, :start_service

    new-instance v0, Lcom/tbruyelle/rxpermissions/RxPermissions;
    invoke-direct {v0, p0}, Lcom/tbruyelle/rxpermissions/RxPermissions;-><init>(Landroid/app/Activity;)V

    const/4 v1, 0x2
    new-array v1, v1, [Ljava/lang/String;
    const/4 v2, 0x0
    const-string v3, "android.permission.BLUETOOTH_CONNECT"
    aput-object v3, v1, v2
    const/4 v2, 0x1
    const-string v3, "android.permission.BLUETOOTH_SCAN"
    aput-object v3, v1, v2

    invoke-virtual {v0, v1}, Lcom/tbruyelle/rxpermissions/RxPermissions;->request([Ljava/lang/String;)Lrx/Observable;
    move-result-object v0
    new-instance v1, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;
    invoke-direct {v1, p0}, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;-><init>(Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;)V
    invoke-virtual {v0, v1}, Lrx/Observable;->subscribe(Lrx/functions/Action1;)Lrx/Subscription;
    return-void

    :start_service
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/yc/gloryfitpro/services/MainService;
    invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    :try_start_0
    invoke-virtual {p0, v0}, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;->startForegroundService(Landroid/content/Intent;)Landroid/content/ComponentName;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0
    return-void

    :catch_0
    move-exception v0
    return-void
'@

$mainActivityDir = Split-Path -Parent $mainActivitySmali
$permissionActionSmali = Join-Path $mainActivityDir 'MainActivity$PrivacyBluetoothPermissionAction.smali'
Write-Utf8NoBom $permissionActionSmali @'
.class final Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;
.super Ljava/lang/Object;

.implements Lrx/functions/Action1;

.field private final activity:Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;

.method constructor <init>(Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;)V
    .locals 0

    iput-object p1, p0, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;->activity:Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public call(Ljava/lang/Boolean;)V
    .locals 2

    invoke-virtual {p1}, Ljava/lang/Boolean;->booleanValue()Z
    move-result p1
    if-eqz p1, :done

    iget-object p1, p0, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;->activity:Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;
    new-instance v0, Landroid/content/Intent;
    const-class v1, Lcom/yc/gloryfitpro/services/MainService;
    invoke-direct {v0, p1, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V

    :try_start_0
    invoke-virtual {p1, v0}, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity;->startForegroundService(Landroid/content/Intent;)Landroid/content/ComponentName;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :done
    return-void

    :catch_0
    move-exception p1
    return-void
.end method

.method public bridge synthetic call(Ljava/lang/Object;)V
    .locals 0

    check-cast p1, Ljava/lang/Boolean;
    invoke-virtual {p0, p1}, Lcom/yc/gloryfitpro/ui/activity/main/MainActivity$PrivacyBluetoothPermissionAction;->call(Ljava/lang/Boolean;)V
    return-void
.end method
'@

# The vendor notification-enabled check combines AndroidX's supported result
# with reflection into a hidden AppOps field. That reflection fails or gives a
# stale result on newer Android releases, making Find phone falsely disable
# itself even when the app notification permission and channel are enabled.
$notificationsUtilsSmali = Find-OneEnding 'com/yc/gloryfitpro/utils/NotificationsUtils.smali'
Set-SmaliMethod $notificationsUtilsSmali '.method public isNotificationEnabled(Landroid/content/Context;)Z' @'
    .locals 1

    invoke-static {p1}, Landroidx/core/app/NotificationManagerCompat;->from(Landroid/content/Context;)Landroidx/core/app/NotificationManagerCompat;
    move-result-object v0
    invoke-virtual {v0}, Landroidx/core/app/NotificationManagerCompat;->areNotificationsEnabled()Z
    move-result v0
    return v0
'@

# Glyphix is an online watch-face SDK. Its constructor queries Android network
# state when a watch connection changes and crashes if ACCESS_NETWORK_STATE is
# absent. Keep the SDK inert instead of restoring network visibility.
$glyphixUtilSmali = Find-OneEnding 'com/yc/gloryfitpro/utils/GlyphixUtil.smali'
Set-SmaliMethod $glyphixUtilSmali '.method private constructor <init>()V' @'
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
'@
Set-SmaliMethod $glyphixUtilSmali '.method public connectDevice()V' @'
    .locals 0

    return-void
'@
Set-SmaliMethod $glyphixUtilSmali '.method public disconnectDevice()V' @'
    .locals 0

    return-void
'@

# Disable app logging to logcat and its plaintext external-cache log files.
$uteLogSmali = Find-OneEnding 'com/yc/gloryfitpro/log/UteLog.smali'
$uteLogText = [System.IO.File]::ReadAllText($uteLogSmali)
$uteLogText = $uteLogText.Replace('.field private static DEBUG:Z = true', '.field private static DEBUG:Z = false')
$uteLogText = $uteLogText.Replace('.field private static PRINT:Z = true', '.field private static PRINT:Z = false')
Write-Utf8NoBom $uteLogSmali $uteLogText
Set-SmaliMethod $uteLogSmali '.method public static setLogEnable(Z)V' "    .locals 0`r`n`r`n    return-void"
Set-SmaliMethod $uteLogSmali '.method public static setPrintEnable(Z)V' "    .locals 0`r`n`r`n    return-void"
Set-SmaliMethod $uteLogSmali '.method public static setPrintEnable(ZLjava/lang/String;)V' "    .locals 0`r`n`r`n    return-void"

# Neutralize direct analytics calls that are sprinkled throughout UI classes,
# even though Android will also deny every network socket without INTERNET.
$mobclickSmali = Find-OneEnding 'com/umeng/analytics/MobclickAgent.smali'
Disable-PublicStaticVoidMethods $mobclickSmali

$umConfigureSmali = Find-OneEnding 'com/umeng/commonsdk/UMConfigure.smali'
Disable-PublicStaticVoidMethods $umConfigureSmali
Set-SmaliMethod $umConfigureSmali '.method public static getUMIDString(Landroid/content/Context;)Ljava/lang/String;' @'
    .locals 1

    const-string v0, ""
    return-object v0
'@

Write-Output "Privacy patch applied to $decoded"
