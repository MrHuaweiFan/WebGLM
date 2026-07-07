# Building the APK on GitHub Actions

This fork has been set up so the APK is built automatically on GitHub's servers — no Android Studio required.

## What the workflow does

`.github/workflows/build-apk.yml` runs on every push to `master` / `main`, every pull request, and on demand via the **Actions → Build APK → Run workflow** button.

It:
1. Spins up an `ubuntu-latest` runner.
2. Installs JDK 11 (Temurin) and Gradle 6.5 — matching the project's old toolchain.
3. Runs `./gradlew assembleDebug` and `./gradlew assembleRelease`.
4. Uploads **two artifacts** you can download from the run page:
   - `app-debug-apk` — `app-debug.apk` (signed with the debug key, ready to install)
   - `app-release-apk` — `app-release.apk` (signed with the bundled `app/test.keystore`)

## How to use it

1. Create a new (empty) repository on GitHub, e.g. `my-webview-app`.
   - Do **not** initialize it with a README / .gitignore / license — keep it empty.
2. Push this folder to that repository:
   ```bash
   git init
   git add .
   git commit -m "Initial commit — webview app with GitHub Actions build"
   git branch -M main
   git remote add origin https://github.com/<your-user>/my-webview-app.git
   git push -u origin main
   ```
3. Open the repo on GitHub → **Actions** tab → **Build APK** workflow → watch the run.
4. When the run finishes, scroll down to **Artifacts** and download the APK you want.

## Signing notes

The release APK is currently signed with `app/test.keystore` (alias `test`, password `test123`). This is fine for personal use and testing, but the key is public in your repo — anyone can sign apps with the same identity.

For a production app, replace the keystore with your own and either:
- Commit it the same way (still public, but at least it's *your* key), or
- Encode it as base64, store it in GitHub Secrets, and decode it in the workflow. An example is included as a comment block at the bottom of `build-apk.yml`.

## Local build (optional)

You don't need this — that's the whole point of the workflow — but if you want to build locally:

```bash
# Requires JDK 11 and Android SDK with platform-android-29
./gradlew assembleDebug
./gradlew assembleRelease
```

The APKs land in `app/build/outputs/apk/{debug,release}/`.

## Customizing the app

- **Web URL**: edit `String url = "https://chat.z.ai/";` in
  `app/src/main/java/com/wekanapp/app/MainActivity.java`.
- **Allowed hostname for in-app navigation**: edit `hostname = "chat.z.ai";`
  in `MyWebViewClient.java`.
- **App name**: edit `<string name="app_name">wekanapp</string>` in
  `app/src/main/res/values/strings.xml`.
- **App icon**: replace the `ic_launcher.png` files under
  `app/src/main/res/mipmap-*/`.
- **Version**: edit `versionCode` / `versionName` in `app/build.gradle`.
