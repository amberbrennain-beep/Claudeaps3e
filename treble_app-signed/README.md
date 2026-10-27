# TrebleApp.apk (rebuilt and signed)

Rebuilt from https://github.com/Sts0mrg0/treble_app at commit `def4ac9`.

- Package: `me.phh.treble.app`, versionName 1.0, minSdk 27, targetSdk 33, compileSdk 35
- Built with `gradle assembleRelease` (Gradle 8.14.3, AGP 8.4.1, JDK 21, build-tools 35.0.0)
- Zipaligned, then signed with the repo's `keys/platform.pk8` / `keys/platform.x509.pem`
  (AOSP test platform key, the same certificate as the upstream `TrebleApp.apk`)
- Signature schemes: v1 + v2 + v3
- Cert SHA-256: `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`
- APK SHA-256: `a97561aa3b8181459e86f3adfdc44db158c4e0ca282beb8e608fc3218c28f9dd`

## Reproduce

```sh
export ANDROID_HOME=/path/to/sdk   # platforms;android-35, build-tools;35.0.0
gradle -I mirror.gradle -Dorg.gradle.java.home=$JAVA_HOME assembleRelease
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --v1-signing-enabled true --key keys/platform.pk8 \
    --cert keys/platform.x509.pem --out TrebleApp.apk aligned.apk
```

`mirror.gradle` only redirects Maven Central to Google's mirror (Maven Central was
rate-limiting the build host); it is optional.
