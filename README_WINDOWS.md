# Depth Measure — Windows Build & Run Guide

ARCore depth-measurement app (Java, Android) built and verified on Windows 11.
Everything installs under **`D:\Android`** — nothing is placed on `C:`.

## What's installed (verified)

| Tool | Version | Location |
|---|---|---|
| Microsoft OpenJDK | 17.0.19 LTS | `D:\Android\jdk\jdk-17.0.19+10` |
| Android SDK cmdline-tools | 11076708 | `D:\Android\sdk\cmdline-tools\latest` |
| platform-tools (adb) | 1.0.41 | `D:\Android\sdk\platform-tools` |
| Android platform | android-34 | `D:\Android\sdk\platforms` |
| build-tools | 34.0.0 | `D:\Android\sdk\build-tools` |
| Gradle (for wrapper gen) | 8.7 | `D:\Android\gradle\gradle-8.7` |

**Why JDK 17, not 11?** Android Gradle Plugin 8.x (required for `compileSdk 34`)
will not run on JDK 11. The app still compiles at **Java 11 source/target**
compatibility as required — that's independent of the JDK running Gradle.

Environment variables set at **User** scope by `setup_windows.ps1`:

```
JAVA_HOME    = D:\Android\jdk\jdk-17.0.19+10
ANDROID_HOME = D:\Android\sdk
Path        += %JAVA_HOME%\bin ; %ANDROID_HOME%\platform-tools ; %ANDROID_HOME%\cmdline-tools\latest\bin
```

> There is **no Python-style virtual environment** here. Isolation comes from the
> Gradle wrapper (pins Gradle 8.7 per-project) plus JDK/SDK on PATH.

## Prerequisites (one-time)

1. **Allow scripts to run** (default policy is `Restricted`, which blocks `.ps1`):
   ```powershell
   Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
   ```
2. **Internet connection** for the first build (downloads AGP, AndroidX, ARCore).

## From-scratch setup

```powershell
# From the project folder (D:\MY_CLOUD_DRIVE\COLLEGE\PROJECTS\DepthMeasure)
.\setup_windows.ps1          # installs JDK17 + SDK under D:\Android
# Close and REOPEN PowerShell so the new PATH/JAVA_HOME take effect
java -version                 # should print 17.0.x
adb version                   # should print 1.0.41
```

## Open in Android Studio (optional)

Android Studio is **not installed** (command-line build only). If you want the IDE
later, install it and **Open** this folder — the Gradle skeleton
(`settings.gradle`, `app/build.gradle`, `gradlew.bat`, `gradle/wrapper/`) is
already scaffolded, so it opens directly. Point its JDK at `D:\Android\jdk\...`.

## Build / install / run / logs

The helper wraps `gradlew.bat` + `adb`. Run it from the project root:

```powershell
.\build_deploy_windows.ps1 build        # assembleDebug -> app-debug.apk
.\build_deploy_windows.ps1 device       # list connected devices + details
.\build_deploy_windows.ps1 install      # installDebug (device required)
.\build_deploy_windows.ps1 run          # launch MainActivity
.\build_deploy_windows.ps1 logs         # stream logcat (tag: DepthMeasure)
.\build_deploy_windows.ps1 full         # build -> install -> run -> logs
```

Equivalent raw commands (what the script runs):

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.depthmeasure/.MainActivity
adb logcat -s DepthMeasure
```

APK output: `app\build\outputs\apk\debug\app-debug.apk` (~6.4 MB, verified).

## Connect the Samsung S25

1. On the phone: Settings → About phone → tap **Build number** 7× to enable
   Developer options.
2. Developer options → enable **USB debugging**.
3. Plug in via USB-C, tap **Allow** on the "Allow USB debugging?" prompt.
4. Verify: `adb devices` → the phone should show as `device` (not `unauthorized`).
5. The S25 needs **Google Play Services for AR** (ARCore) — Play Store will
   prompt to install it on first launch if missing.

## Measurement logging (in the app)

- Every valid center-depth reading is appended to an in-memory history
  (last **20** kept) and logged: `adb logcat -s DepthMeasure`.
- The **Share Log** button exports the history as CSV
  (`timestamp,depth_m,confidence`) via the Android share sheet.

## Troubleshooting (issues actually hit)

| Problem | Cause / Fix |
|---|---|
| `running scripts is disabled on this system` | Policy is `Restricted`. Run `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser`. |
| Gradle sync/build fails: "requires Java 17" | You're on JDK 11. This project uses JDK 17 (`JAVA_HOME=D:\Android\jdk\...`). |
| `java`/`adb` not recognized | PATH not refreshed — **close and reopen** PowerShell after setup. |
| `stripDebugDebugSymbols: Unable to strip libarcore_sdk_*.so` | **Harmless warning** — ARCore ships prebuilt `.so`s; build still succeeds. |
| Device shows `unauthorized` | Unplug/replug, tap **Allow** on the phone's USB-debugging prompt. |
| Build corruption / file locks | This folder is on a **cloud-synced drive**. If sync causes Gradle lock errors, pause syncing during builds or move the project to a non-synced path. |
| `adb devices` empty | Bad cable / USB debugging off / driver. Try a different cable and re-check Developer options. |
```
