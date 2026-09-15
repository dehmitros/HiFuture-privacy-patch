# Private weather for the Aurora watch

Weather travels from a small separate Android companion into the offline HiFuture Fit app, then over its existing Bluetooth connection to the watch. HiFuture Fit still has no `INTERNET` permission. The companion cannot read its health database and has no GPS, Bluetooth, health, contacts, SMS, or notification-listener permissions.

The companion uses [Open-Meteo's forecast API](https://open-meteo.com/en/docs) without an account or API key.

This minimizes disclosure; it does not make requests anonymous. Open-Meteo receives the phone's public IP and coordinates rounded to 0.1 degrees (approximately 11 km in latitude; longitude distance varies). Its [privacy policy](https://open-meteo.com/en/terms) allows request logs containing coordinates for 90 days. The local location label is never sent to Open-Meteo. No vendor account, watch identifier, health data, analytics, advertising SDK, geocoder, or automatic GPS lookup is used. HTTPS redirects are rejected. The companion has no external fetch/configuration endpoint: opening its launcher cannot trigger a request before weather has been enabled locally.

Weather is off on first launch. Choose a location label and latitude/longitude, switch on hourly weather, and tap **Save and refresh**. Coordinates are rounded before being saved. Open the companion from the app drawer or HiFuture Fit's weather tile. Enable Bluetooth/pair the watch in HiFuture Fit as usual. Both APKs must be signed with the same private key; install HiFuture Fit first.

The companion fetches current temperature, humidity and condition; seven daily forecasts with high/low temperatures, sunrise/sunset and UV; and up to 24 hourly forecasts. It always supplies Celsius internally; the existing watch sync applies HiFuture Fit's temperature-unit preference. The companion preview uses Celsius. WMO condition codes are passed through the app's existing converter for RK and Nadal watch formats. Available watch screens depend on its firmware.

Automatic updates use Android JobScheduler about once per hour and survive reboot. Android battery restrictions, force stopping the app, and missing connectivity can delay updates. Manual refresh is limited to once per minute. Fetches have bounded response sizes and timeouts. A failed refresh keeps the previous forecast until it expires. Phone-to-watch delivery is one-way and protected by a signature-level permission. The bridge validates the format, bounds and timestamps before storing anything. Existing reconnect and temperature-change sync paths refuse cached weather older than three hours. They send a fresh cached forecast when the watch reconnects, without fetching through HiFuture Fit.

Turning weather off cancels automatic updates and clears weather snapshots in both apps. An already-started network request can complete, but its result is discarded after opt-out. Rounded location settings remain for reuse. Watch firmware may keep its last weather display until its own expiry; this patch cannot guarantee immediate clearing of the watch screen. Air quality, moon information and daily humidity are not provided; the existing Bluetooth protocol may show zero/default values for those fields. Do not interpret those defaults as measurements.

Weather data is attributed to Open-Meteo under CC BY 4.0 in the companion and the app's stored source field. The public free API is for non-commercial use; commercial distribution needs an appropriate Open-Meteo service arrangement.

## Build

Follow the main README to decode the original supported HiFuture Fit 1.5.1.5 ARM64 APK and apply `scripts/apply_privacy_patch.ps1`. Before rebuilding that APK, run the weather build below. Java 17+ and the existing Apktool are required, along with Android API 35 and Build Tools 35.0.1. These are Google's SDK packages; review/accept their SDK licensing before use.

```powershell
New-Item -ItemType Directory -Force tools/android-sdk | Out-Null
java tools/Download.java https://dl.google.com/android/repository/platform-35_r02.zip tools/android-sdk/platform-35.zip
java tools/Download.java https://dl.google.com/android/repository/build-tools_r35.0.1_windows.zip tools/android-sdk/build-tools-35.zip
Expand-Archive tools/android-sdk/platform-35.zip tools/android-sdk/platform-35
Expand-Archive tools/android-sdk/build-tools-35.zip tools/android-sdk/build-tools-35
powershell -ExecutionPolicy Bypass -File scripts/build_weather.ps1 -DecodedDir work/base-decoded
```

Then rebuild, merge and sign HiFuture Fit with the main README's instructions. Separately sign the companion with the same private key:

```powershell
java -jar tools/uber-apk-signer-1.3.0.jar -a work/weather/companion/unsigned.apk -o dist/weather-companion --ks signing/hifuture-privacy.p12 --ksAlias hifuture-privacy
```

Install the updated HiFuture Fit first, then the signed companion. Existing privacy installations can be updated with `adb install -r` when signed with the same key. Keep the original offline APK if you want to remove the optional bridge later. Neither key material nor vendor APKs are included in this repository.

## Verification

The host tests check coordinate rounding and invalid inputs, WMO mapping, stale/malformed snapshots, payload bounds, and a live forecast response for the public Berlin example. This requests Berlin weather, not your location.

```powershell
java tools/Download.java https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar tools/android-sdk/json-test.jar
javac -encoding UTF-8 -classpath tools/android-sdk/json-test.jar -d work/weather-tests weather/shared/org/hifuture/weather/WeatherSnapshot.java weather/tests/WeatherSnapshotTest.java
java -classpath 'work/weather-tests;tools/android-sdk/json-test.jar' WeatherSnapshotTest
```

For optional device integration tests, build with `-BuildDeviceTests`, sign `work/weather/tests/unsigned.apk` with the same local key, install it after the updated HiFuture Fit and companion, then run:

```powershell
tools/platform-tools/adb.exe shell am instrument -w org.hifuture.weather.test/.BridgeInstrumentation
tools/platform-tools/adb.exe uninstall org.hifuture.weather.test
```

The device test temporarily delivers synthetic weather, checks conversion and rejection of invalid/expired snapshots, and clears the weather cache afterward. It makes no network requests. It verifies the Android bridge, not what appears on the physical watch. Also verify that the rebuilt HiFuture Fit manifest lacks Internet permission and that a broadcast from the adb shell is denied by the signature permission.
