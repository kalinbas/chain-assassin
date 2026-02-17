# AGENTS.md

## Android Build Rule

- Always run Android builds through the project script:
  - `./scripts/android-gradle.sh`
- Do not assume `./gradlew` exists.
- Do not assume system `gradle` is installed.

## Common Android Commands

- Compile Kotlin:
  - `./scripts/android-gradle.sh :app:compileDebugKotlin`
- Build debug APK:
  - `./scripts/android-gradle.sh :app:assembleDebug`
- Run instrumentation tests:
  - `./scripts/android-gradle.sh :app:connectedDebugAndroidTest`

## Server Deploy Rule

- Always deploy the server using local Docker build from the `server` directory:
  - `fly deploy --local-only`
- Prefer local Docker deploy over remote builders unless explicitly requested otherwise.
