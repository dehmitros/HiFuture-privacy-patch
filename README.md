# HiFuture Aurora / Fit 1.5.1.5 privacy patch

This project was made for the HiFuture Aurora smartwatch, it patches the Android HiFuture Fit app into an offline build. Local Bluetooth watch functions are preserved. Internet access, analytics, advertising, cloud services, and several third-party integrations are yanked out. An optional [private weather companion](weather/README.md) supplies forecasts without giving HiFuture Fit Internet access.

## What you need

Install Java 17 or newer and Windows PowerShell, then download these three tools:

- Apktool 3.0.3 — save it as `tools/apktool_3.0.3.jar`
- APKEditor 1.4.9 — save it as `tools/APKEditor-1.4.9.jar`
- uber-apk-signer 1.3.0 — save it as `tools/uber-apk-signer-1.3.0.jar`

Android SDK Platform Tools is optional. It provides `adb`, which is useful for installing the APK and diagnosing crashes over USB.

You also need an **arm64-v8a** HiFuture Fit `1.5.1.5` XAPK. `arm64-v8a` is the tested 64-bit ARM architecture; it is not the older `armeabi-v7a`/ARMv7 variant. The app file cannot be included in this repository due to Github's TOS. Search the web for:

```text
HiFuture Fit 1.5.1.5 APKPure XAPK arm64-v8a
```

Only download and modify an application you are legally allowed to use. Before patching, verify that its package is `com.cs.ute.hiFuture`, its version is `1.5.1.5`, and its signature is the original vendor signature.

Put the XAPK in the repository folder and rename it to `HiFutureFit-1.5.1.5-arm64-v8a.xapk`.

## Patching:

Open PowerShell in the repository folder and run each block in order.

### 1. Extract the XAPK

```powershell
New-Item -ItemType Directory -Force .\work\bundle | Out-Null
Copy-Item .\HiFutureFit-1.5.1.5-arm64-v8a.xapk .\work\bundle.zip
Expand-Archive -Force .\work\bundle.zip .\work\bundle
```

The bundle should contain a base APK named `com.cs.ute.hiFuture.apk` and an `arm64-v8a` configuration split.

### 2. Decode and patch the base APK

```powershell
java -jar .\tools\apktool_3.0.3.jar d -f .\work\bundle\com.cs.ute.hiFuture.apk -o .\work\base-decoded
powershell -ExecutionPolicy Bypass -File .\scripts\apply_privacy_patch.ps1 -DecodedDir .\work\base-decoded
java -jar .\tools\apktool_3.0.3.jar b -f .\work\base-decoded -o .\work\bundle\com.cs.ute.hiFuture.apk
```

### 3. Merge the patched base and splits

```powershell
java -jar .\tools\APKEditor-1.4.9.jar m -i .\work\bundle -o .\work\HiFutureFit-1.5.1.5-privacy-unsigned.apk -f
```

### 4. Create a private signing key

Android requires the rebuilt APK to be signed. Create your own key and keep it private:

```powershell
New-Item -ItemType Directory -Force .\signing | Out-Null
keytool -genkeypair -keystore .\signing\hifuture-privacy.p12 -storetype PKCS12 -alias hifuture-privacy -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` will prompt for a password and certificate details. Do not publish the key or password. Back up both securely: every future update must use the same key.

### 5. Sign and verify the APK

```powershell
New-Item -ItemType Directory -Force .\dist | Out-Null
java -jar .\tools\uber-apk-signer-1.3.0.jar -a .\work\HiFutureFit-1.5.1.5-privacy-unsigned.apk -o .\dist --ks .\signing\hifuture-privacy.p12 --ksAlias hifuture-privacy
```

The signer prompts for the password instead of placing it in shell history. It produces an APK ending in `-aligned-signed.apk` inside `dist`. Verify it with:

```powershell
java -jar .\tools\uber-apk-signer-1.3.0.jar -a .\dist\HiFutureFit-1.5.1.5-privacy-unsigned-aligned-signed.apk -y
```

## Install

The privacy build has a different signature from the official app, so Android cannot install it as an update to the official version. Back up anything important, uninstall the official HiFuture Fit app, and install the newly signed APK.

With USB debugging and Android SDK Platform Tools, installation can be tested with:

```powershell
.\tools\platform-tools\adb.exe install .\dist\HiFutureFit-1.5.1.5-privacy-unsigned-aligned-signed.apk
```

Do not uninstall a working privacy build before an update. Rebuild and sign the update with the same private key, then use `adb install -r` or install it normally over the existing privacy build.

## Expected limitations

Online features do not work: vendor weather, cloud sync, online watch faces, remote firmware/config downloads, online maps, email/social login, feedback upload, and update checks. Weather can be restored with the optional companion. Some now-inert buttons may remain visible.

The missing Android Internet permission is the primary egress control. The original app's large codebase still contains unreachable vendor networking code. Local health data remains in ordinary SQLite storage protected by Android's app sandbox and device encryption, not a separate database password.