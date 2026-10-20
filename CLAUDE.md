# CLAUDE.md - Project Context & Automation Guide

## Architecture
- **Target**: Android On-Device Real-Time Speech Translator (English, Japanese, Mandarin Chinese, Spanish -> Korean)
- **Auto Language Detection**:
  1. Android 14+ `SpeechRecognizer` audio-level language switching (`EXTRA_ENABLE_LANGUAGE_SWITCH`)
  2. Script regex + ML Kit On-Device Language Identification (`com.google.mlkit:language-id:17.0.6`)
- **Translation Engine**: On-device Gemini Nano via ML Kit GenAI Prompt API (`com.google.mlkit:genai-prompt:1.0.0-beta4`, built with Kotlin 2.3 metadata, so the project uses Kotlin 2.3.21) using `generateContentStream()`
- **Audio Output**: `TextToSpeech` (Korean) routed via `USAGE_MEDIA` so Bluetooth earphones receive translated audio while the phone microphone captures external speech. Includes an echo-guard when running on the built-in speaker.
- **App info / update**: `AppInfo.kt` (name, version, author 이현덕 (hdlee73@gmail.com), in-app update notes), `UpdateManager.kt` (checks GitHub Releases `latest`, downloads the non-debug APK, installs via FileProvider).
- **Back navigation**: sub-screens return to the previous screen via `BackHandler`; on the main screen a second back press within 2s exits.

## Versioning & Signing
- `versionName` comes from the tag (`-PappVersionName=X.Y.Z` in CI); `versionCode = X*10000 + Y*100 + Z`.
- Release signing reads `keystore/keystore.properties` if present, otherwise env vars `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (CI fills these from repo Secrets, keystore from `RELEASE_KEYSTORE_BASE64`). Always sign with the same key so updates install over the previous version.
- When releasing, add the new version's notes at the top of `AppInfo.updateNotes`.

## Build & Release Commands
- Compile debug APK: `./gradlew assembleDebug`
- Compile release APK: `./gradlew assembleRelease`
- Trigger GitHub Release: `git tag v1.0.1 && git push origin v1.0.1`
