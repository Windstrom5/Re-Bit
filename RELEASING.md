# Building a release APK

## 1. Create your signing key (once)

Run in a terminal. Keep the file OUTSIDE the project folder, for example `D:\Project\keys\`.

    "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v ^
      -keystore D:\Project\keys\rebit-release.jks -alias rebit ^
      -keyalg RSA -keysize 2048 -validity 10000

It asks for a keystore password, a key password and your name. Type them in the prompt; they
are not echoed. If `keytool` is "not recognized", use the full path to the `jbr\bin` folder of
your Android Studio install (or any JDK 17).

**Back up the .jks file and both passwords somewhere safe (password manager + a copy offline).**
If you lose them you can never publish an update to the same app: users would have to uninstall.
Never commit them and never send them to anyone.

## 2. Tell Gradle where it is

Copy `keystore.properties.example` to `keystore.properties` and fill in the real values.

## 3. Build

There are two editions:

| Edition | Command | APK | Notes |
|---|---|---|---|
| lite | `gradlew assembleLiteRelease` | `app\build\outputs\apk\lite\release\app-lite-release.apk` | Smallest. No neural vocal separator; karaoke/stem separation uses the built-in signal-processing path. |
| full | `gradlew assembleFullRelease` | `app\build\outputs\apk\full\release\app-full-release.apk` | Everything, including the on-device Spleeter model. |

    gradlew --stop
    gradlew assembleLiteRelease

Release builds contain only the `arm64-v8a` CPU architecture. For an old 32-bit-only phone add
`-PrebitAbis=arm64-v8a,armeabi-v7a` (the APK grows accordingly).

Check what is taking space in Android Studio: **Build > Analyze APK...** and open the release APK.

Install on a phone: `adb install -r <apk>`. Debug builds: `gradlew installFullDebug` or `gradlew installLiteDebug`.
For Google Play, build a bundle instead: `gradlew bundleFullRelease` (Play also delivers only the user's own CPU architecture).

## Every release

Raise `versionCode` in `app/build.gradle.kts` by 1 (Android refuses to update to the same or a
lower number) and change `versionName` (currently `1.0.0-beta.1`).
