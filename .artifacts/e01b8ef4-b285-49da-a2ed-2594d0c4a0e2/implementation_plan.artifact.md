# Fix Unresolved reference: BuildConfig

The `BuildConfig` class is not being generated, which is a common behavior in recent Android Gradle Plugin (AGP) versions where it is disabled by default for better performance.

## User Review Required

> [!NOTE]
> I will be enabling the `buildConfig` generation in the `:app` module. This is necessary because the application uses `BuildConfig.VERSION_CODE` to check for updates.

## Proposed Changes

### Build Configuration

#### [MODIFY] [build.gradle.kts](file:///C:/Projetos/UPImagens/app/build.gradle.kts)
- Enable `buildConfig` in the `buildFeatures` block.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:compileDebugKotlin` to verify that the error is resolved.
