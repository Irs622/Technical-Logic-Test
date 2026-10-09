# Build & Deployment Guide

## 1. Environment Configurations
- **Debug:** Logging enabled, mock server togglable, applicationId suffix `.debug`.
- **Release:** ProGuard/R8 enabled, minified and shrunk resources, strict security rules.

## 2. Keystore & Signing
- Place production keystores in a secure environment outside version control.
- Supply keystore passwords via environment variables or CI/CD secrets:
  - `ANDROID_KEYSTORE_BASE64`
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS`
  - `ANDROID_KEY_PASSWORD`

## 3. CI/CD Pipeline
- Pull Request Checks:
  1. `./gradlew lintDebug`
  2. `./gradlew testDebugUnitTest`
  3. `./gradlew assembleDebug`
- Release Build:
  - `./gradlew bundleRelease`
  - Distribute via Google Play Console internal track.
