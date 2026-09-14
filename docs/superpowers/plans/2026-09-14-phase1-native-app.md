# Phase 1 Native Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Phase 1 native Android app described in `docs/superpowers/specs/2026-09-14-phase1-native-app-design.md` — a single-screen Kotlin app with feature parity to `ask-gemini.js` minus voice, replacing the Termux CLI as the delivery mechanism.

**Architecture:** Single-Activity, single-screen Compose app in a new `android-app/` subdirectory (Termux CLI at the repo root untouched). MVVM with unidirectional data flow, Hilt constructor injection, repository pattern wrapping the `google-genai-kotlin` SDK call. `ui/` and `data/` packages, no `domain` layer (nothing to share across ViewModels yet).

**Tech Stack:** Kotlin 2.4.20, Jetpack Compose (BOM 2026.08.00), Hilt 2.57.1 (kapt, not KSP — see Global Constraints), `com.google.genai:google-genai-kotlin:1.1.0`, AGP 9.4.0 / Gradle 9.6, JUnit 4 + kotlinx-coroutines-test with hand-written fakes.

## Global Constraints

- `applicationId` / base package: `com.ameyapb.androidexp`.
- `minSdk` 31; `compileSdk` = `targetSdk` = 36 (current stable; AGP 9.4.0 supports up to API 37, but 36 is the confirmed-available stable platform).
- Same model/system instruction as `ask-gemini.js`: `GEMINI_MODEL_NAME = "gemini-3.1-flash-lite"`, `GEMINI_SYSTEM_INSTRUCTION` verbatim from `ask-gemini.js:37`.
- Notification title "Gemini", matching `TERMUX_NOTIFICATION_TITLE`.
- API key: `android-app/local.properties` (gitignored) → `BuildConfig.GEMINI_API_KEY`. Never hardcoded, never logged.
- Failed Gemini calls show as inline error text only; no error notification; send button re-enables.
- Use kapt (bundled with the Kotlin Gradle plugin, no separate version to pin) for the Hilt annotation processor rather than KSP, to avoid a fragile cross-pinned KSP-release-vs-Kotlin-release lookup for a single-module personal app where kapt's extra build time is a non-issue.
- One addition beyond the design spec's explicit dependency list: `androidx.hilt:hilt-navigation-compose:1.4.0`, the standard glue needed to call `hiltViewModel()` from a `@Composable` — required by the already-decided Hilt + Compose MVVM architecture, not a new architectural choice.
- One addition beyond the design spec's file layout: `data/notification/GeminiNotifier.kt` is split into a `GeminiNotifier` interface + `GeminiNotifierImpl`, mirroring the existing `GeminiClient`/`GeminiClientImpl` split, so `AskGeminiViewModel` can be tested against a fake notifier instead of a mock (per the Testing section of `CLAUDE.md`).
- No app launcher icon resource is created (manifest omits `android:icon`); this is a sideloaded personal app, and default system icon is an accepted simplification, not a placeholder.
- Gradle wrapper pinned to `9.7.1` (current stable; AGP 9.4.0 requires 9.1+, and `gradle-9.6-bin.zip` turned out not to exist as a distribution — Gradle's actual naming is `gradle-<version>-bin.zip` with a full patch version).
- **Discovered during implementation, not in the original design:** AGP 9's built-in Kotlin support means the `org.jetbrains.kotlin.android` plugin must NOT be applied (it now conflicts). Compose still needs the separate `org.jetbrains.kotlin.plugin.compose` plugin (version tied to the Kotlin version). `org.jetbrains.kotlin.kapt` is incompatible with built-in Kotlin; use `com.android.legacy-kapt` (version tied to the AGP version) instead. Hilt's Gradle plugin only gained AGP 9 support from **2.59.2 onward** (2.57.1, the version originally planned, fails with "Android BaseExtension not found") — this plan uses Hilt **2.60.1**. All of this was verified against a real local Gradle 9.7.1 + AGP 9.4.0 toolchain, not just documentation, precisely because AGP 9 built-in Kotlin is new enough (GA January 2026) that written docs and this plan's own initial research were incomplete/wrong on these points.

---

### Task 1: Gradle project scaffold

**Files:**
- Create: `android-app/settings.gradle.kts`
- Create: `android-app/build.gradle.kts`
- Create: `android-app/gradle.properties`
- Create: `android-app/gradle/libs.versions.toml`
- Create: `android-app/gradle/wrapper/gradle-wrapper.properties`
- Create: `android-app/gradlew`
- Create: `android-app/gradlew.bat`
- Create: `android-app/.gitignore`
- Create: `android-app/local.properties.example`

**Interfaces:**
- Produces: version catalog aliases used by every later Gradle file: `libs.plugins.android.application`, `libs.plugins.kotlin.android`, `libs.plugins.kotlin.kapt`, `libs.plugins.hilt`, `libs.genai.kotlin`, `libs.compose.bom`, `libs.hilt.android`, `libs.hilt.compiler`, `libs.hilt.navigation.compose`, `libs.coroutines.android`, `libs.core.ktx`, `libs.lifecycle.viewmodel.compose`, `libs.activity.compose`, `libs.coroutines.test`, `libs.junit`.

- [ ] **Step 1: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.20"
hilt = "2.57.1"
genai = "1.1.0"
composeBom = "2026.08.00"
coreKtx = "1.17.0"
lifecycle = "2.11.0"
activityCompose = "1.12.4"
hiltNavigationCompose = "1.4.0"
coroutines = "1.11.0"
junit = "4.13.2"

[libraries]
genai-kotlin = { group = "com.google.genai", name = "google-genai-kotlin", version.ref = "genai" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-kapt = { id = "org.jetbrains.kotlin.kapt", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android-exp-integration"
include(":app")
```

- [ ] **Step 3: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5: Write `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 6: Place the Gradle wrapper jar and launcher scripts**

Fetch the real wrapper jar (do not hand-author binary content):

```bash
curl -sSL -o android-app/gradle/wrapper/gradle-wrapper.jar \
  "https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar"
```

Write the standard (version-independent) `gradlew` and `gradlew.bat` launcher scripts — use the canonical Gradle wrapper scripts.

- [ ] **Step 7: Write `android-app/.gitignore`**

```
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build/
/captures/
.externalNativeBuild/
.cxx/
*.apk
```

- [ ] **Step 8: Write `local.properties.example`**

```properties
GEMINI_API_KEY=your-google-ai-studio-key-here
```

- [ ] **Step 9: Verify the scaffold parses**

Run: `cd android-app && ./gradlew help`
Expected: Gradle downloads the 9.6 distribution and prints the standard task list (no `:app` module yet, so this only proves the wrapper/settings/root build files are syntactically valid).

- [ ] **Step 10: Commit**

```bash
git add android-app/settings.gradle.kts android-app/build.gradle.kts android-app/gradle.properties \
  android-app/gradle/ android-app/gradlew android-app/gradlew.bat android-app/.gitignore \
  android-app/local.properties.example
git commit -m "Scaffold android-app Gradle project"
```

---

### Task 2: App module scaffold, manifest, Hilt application, API key plumbing

**Files:**
- Create: `android-app/app/build.gradle.kts`
- Create: `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/GeminiApp.kt`
- Create: `android-app/app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: version catalog aliases from Task 1.
- Produces: `BuildConfig.GEMINI_API_KEY: String`, application class `GeminiApp` (registered in the manifest as `android:name=".GeminiApp"`), package `com.ameyapb.androidexp`.

- [ ] **Step 1: Write `app/build.gradle.kts`**

```kotlin
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.ameyapb.androidexp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ameyapb.androidexp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.genai.kotlin)

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 2: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".GeminiApp"
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

- [ ] **Step 3: Write `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Ask Gemini</string>
</resources>
```

- [ ] **Step 4: Write `GeminiApp.kt`**

```kotlin
package com.ameyapb.androidexp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GeminiApp : Application()
```

- [ ] **Step 5: Commit**

```bash
git add android-app/app/build.gradle.kts android-app/app/src/main/AndroidManifest.xml \
  android-app/app/src/main/java/com/ameyapb/androidexp/GeminiApp.kt \
  android-app/app/src/main/res/values/strings.xml
git commit -m "Add app module scaffold, manifest, and Hilt application class"
```

---

### Task 3: Gemini data layer + repository tests

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/GeminiConstants.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/GeminiClient.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/GeminiClientImpl.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/GeminiRepository.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/GeminiRepositoryImpl.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/data/gemini/FakeGeminiClient.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/data/gemini/GeminiRepositoryImplTest.kt`

**Interfaces:**
- Consumes: `com.google.genai.kotlin.Client`, `com.google.genai.kotlin.types.GenerateContentConfig`, `com.google.genai.kotlin.types.Content`, `com.google.genai.kotlin.ClientException`, `com.google.genai.kotlin.ServerException`, `com.google.genai.kotlin.GenAiApiException` (constructor `(code: Int, status: String, message: String)`, exposing `code: Int`).
- Produces: `interface GeminiClient { suspend fun generateContent(prompt: String): String }`; `interface GeminiRepository { suspend fun sendPrompt(prompt: String): Result<String> }`. Later tasks (DI, ViewModel) depend on these two interface names and signatures exactly.

- [ ] **Step 1: Write `GeminiConstants.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

internal const val GEMINI_MODEL_NAME = "gemini-3.1-flash-lite"

internal const val GEMINI_SYSTEM_INSTRUCTION =
    "Reply in plain spoken prose only, as if answering aloud. Do not use markdown, headers, " +
        "bullet points, numbered lists, asterisks, or any other formatting or special characters. " +
        "Keep the reply short and conversational."
```

- [ ] **Step 2: Write `GeminiClient.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

interface GeminiClient {
    suspend fun generateContent(prompt: String): String
}
```

- [ ] **Step 3: Write `GeminiClientImpl.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.Client
import com.google.genai.kotlin.types.Content
import com.google.genai.kotlin.types.GenerateContentConfig
import javax.inject.Inject

class GeminiClientImpl @Inject constructor(private val client: Client) : GeminiClient {
    override suspend fun generateContent(prompt: String): String {
        val response = client.models.generateContent(
            model = GEMINI_MODEL_NAME,
            text = prompt,
            config = GenerateContentConfig(
                systemInstruction = Content.fromText(GEMINI_SYSTEM_INSTRUCTION),
            ),
        )
        return response.text.orEmpty().trim()
    }
}
```

- [ ] **Step 4: Write `GeminiRepository.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

interface GeminiRepository {
    suspend fun sendPrompt(prompt: String): Result<String>
}
```

- [ ] **Step 5: Write `GeminiRepositoryImpl.kt`**

Mirrors `describeGeminiApiError` in `ask-gemini.js:71-79`: 401 is treated as an auth failure, 429 as a rate-limit failure, anything else falls back to a generic message. `ClientException`/`ServerException` both extend `GenAiApiException`, which carries `code: Int` (the HTTP status) — see `com.google.genai.kotlin.Errors.kt` in the `google-genai-kotlin` SDK.

```kotlin
package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.GenAiApiException
import javax.inject.Inject

private const val GEMINI_AUTH_ERROR_HTTP_STATUS = 401
private const val GEMINI_RATE_LIMIT_HTTP_STATUS = 429
private const val GEMINI_API_KEY_PROPERTY_NAME = "GEMINI_API_KEY"

class GeminiRepositoryImpl @Inject constructor(
    private val geminiClient: GeminiClient,
) : GeminiRepository {
    override suspend fun sendPrompt(prompt: String): Result<String> {
        return try {
            Result.success(geminiClient.generateContent(prompt))
        } catch (apiError: GenAiApiException) {
            Result.failure(Exception(describeGeminiApiError(apiError)))
        }
    }
}

internal fun describeGeminiApiError(apiError: GenAiApiException): String {
    return when (apiError.code) {
        GEMINI_AUTH_ERROR_HTTP_STATUS ->
            "Gemini API error: ${apiError.message}. Check that $GEMINI_API_KEY_PROPERTY_NAME in " +
                "local.properties is set to a valid Google AI Studio key."
        GEMINI_RATE_LIMIT_HTTP_STATUS ->
            "Gemini API error: ${apiError.message}. You've hit the free tier rate limit, wait a " +
                "bit and try again."
        else -> "Gemini API error: ${apiError.message}"
    }
}
```

- [ ] **Step 6: Write `FakeGeminiClient.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

class FakeGeminiClient : GeminiClient {
    var responseText: String = "fake reply"
    var errorToThrow: Throwable? = null

    override suspend fun generateContent(prompt: String): String {
        errorToThrow?.let { throw it }
        return responseText
    }
}
```

- [ ] **Step 7: Write `GeminiRepositoryImplTest.kt`**

```kotlin
package com.ameyapb.androidexp.data.gemini

import com.google.genai.kotlin.ClientException
import com.google.genai.kotlin.ServerException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiRepositoryImplTest {
    private lateinit var fakeGeminiClient: FakeGeminiClient
    private lateinit var repository: GeminiRepositoryImpl

    @Before
    fun setUp() {
        fakeGeminiClient = FakeGeminiClient()
        repository = GeminiRepositoryImpl(fakeGeminiClient)
    }

    @Test
    fun `sendPrompt returns the reply text on success`() = runTest {
        fakeGeminiClient.responseText = "the sky is blue"

        val result = repository.sendPrompt("why is the sky blue")

        assertEquals("the sky is blue", result.getOrNull())
    }

    @Test
    fun `sendPrompt maps a 401 to an auth error message`() = runTest {
        fakeGeminiClient.errorToThrow = ClientException(401, "UNAUTHENTICATED", "bad key")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("local.properties"))
    }

    @Test
    fun `sendPrompt maps a 429 to a rate-limit error message`() = runTest {
        fakeGeminiClient.errorToThrow = ClientException(429, "RESOURCE_EXHAUSTED", "too many requests")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("rate limit"))
    }

    @Test
    fun `sendPrompt maps a 500 to a generic error message`() = runTest {
        fakeGeminiClient.errorToThrow = ServerException(500, "INTERNAL", "boom")

        val result = repository.sendPrompt("prompt")

        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.startsWith("Gemini API error:"))
        assertTrue(message.contains("boom"))
    }
}
```

- [ ] **Step 8: Run the repository tests**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest --tests "com.ameyapb.androidexp.data.gemini.GeminiRepositoryImplTest"`
Expected: 4 tests pass.

- [ ] **Step 9: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/data/gemini/ \
  android-app/app/src/test/java/com/ameyapb/androidexp/data/gemini/
git commit -m "Add Gemini data layer with repository error mapping and tests"
```

---

### Task 4: Notification layer

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/notification/GeminiNotifier.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/data/notification/GeminiNotifierImpl.kt`

**Interfaces:**
- Produces: `interface GeminiNotifier { fun notify(replyText: String) }`. Task 6's ViewModel and its `FakeGeminiNotifier` depend on this exact signature.

- [ ] **Step 1: Write `GeminiNotifier.kt`**

```kotlin
package com.ameyapb.androidexp.data.notification

interface GeminiNotifier {
    fun notify(replyText: String)
}
```

- [ ] **Step 2: Write `GeminiNotifierImpl.kt`**

Mirrors the script's `displayResultToUser` notification behavior (`ask-gemini.js:240-257`): failures never block the on-screen reply. `NotificationManagerCompat.areNotificationsEnabled()` covers the case where the user denied `POST_NOTIFICATIONS`; the `SecurityException` catch covers the narrow race where permission is revoked between that check and the `notify()` call.

```kotlin
package com.ameyapb.androidexp.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

private const val GEMINI_NOTIFICATION_TITLE = "Gemini"
private const val NOTIFICATION_CHANNEL_ID = "gemini_replies"
private const val NOTIFICATION_CHANNEL_NAME = "Gemini replies"
private const val NOTIFICATION_ID = 1

class GeminiNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : GeminiNotifier {

    init {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun notify(replyText: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(GEMINI_NOTIFICATION_TITLE)
            .setContentText(replyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(replyText))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (permissionError: SecurityException) {
            return
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/data/notification/
git commit -m "Add Gemini reply notification wrapper"
```

---

### Task 5: Hilt DI module

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/di/AppModule.kt`

**Interfaces:**
- Consumes: `GeminiClient`/`GeminiClientImpl` (Task 3), `GeminiRepository`/`GeminiRepositoryImpl` (Task 3), `GeminiNotifier`/`GeminiNotifierImpl` (Task 4), `BuildConfig.GEMINI_API_KEY` (Task 2).
- Produces: a Hilt-injectable singleton `com.google.genai.kotlin.Client`.

- [ ] **Step 1: Write `AppModule.kt`**

The `GEMINI_API_KEY` blank check makes the app fail fast with a clear message the first time anything needs the Gemini client, rather than surfacing an opaque SDK auth error — Hilt singletons are created lazily on first injection, so this check runs at first send, not at process start.

```kotlin
package com.ameyapb.androidexp.di

import com.ameyapb.androidexp.BuildConfig
import com.ameyapb.androidexp.data.gemini.GeminiClient
import com.ameyapb.androidexp.data.gemini.GeminiClientImpl
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.gemini.GeminiRepositoryImpl
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import com.ameyapb.androidexp.data.notification.GeminiNotifierImpl
import com.google.genai.kotlin.Client
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindGeminiClient(impl: GeminiClientImpl): GeminiClient

    @Binds
    @Singleton
    abstract fun bindGeminiRepository(impl: GeminiRepositoryImpl): GeminiRepository

    @Binds
    @Singleton
    abstract fun bindGeminiNotifier(impl: GeminiNotifierImpl): GeminiNotifier

    companion object {
        @Provides
        @Singleton
        fun provideGenAiClient(): Client {
            check(BuildConfig.GEMINI_API_KEY.isNotBlank()) {
                "GEMINI_API_KEY is not set. Add it to android-app/local.properties " +
                    "(see local.properties.example)."
            }
            return Client(apiKey = BuildConfig.GEMINI_API_KEY)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/di/
git commit -m "Add Hilt DI module wiring the Gemini client, repository, and notifier"
```

---

### Task 6: UI layer + ViewModel tests

**Files:**
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiUiState.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModel.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiScreen.kt`
- Create: `android-app/app/src/main/java/com/ameyapb/androidexp/MainActivity.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/FakeGeminiRepository.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/FakeGeminiNotifier.kt`
- Test: `android-app/app/src/test/java/com/ameyapb/androidexp/ui/askgemini/AskGeminiViewModelTest.kt`

**Interfaces:**
- Consumes: `GeminiRepository.sendPrompt(prompt: String): Result<String>` (Task 3), `GeminiNotifier.notify(replyText: String)` (Task 4).
- Produces: `data class AskGeminiUiState(val promptText: String, val replyText: String, val isLoading: Boolean, val errorMessage: String?)`; `AskGeminiViewModel.onPromptTextChanged(text: String)`, `AskGeminiViewModel.onSendClicked()`, `AskGeminiViewModel.uiState: StateFlow<AskGeminiUiState>`.

- [ ] **Step 1: Write `AskGeminiUiState.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

data class AskGeminiUiState(
    val promptText: String = "",
    val replyText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)
```

- [ ] **Step 2: Write `AskGeminiViewModel.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ameyapb.androidexp.data.gemini.GeminiRepository
import com.ameyapb.androidexp.data.notification.GeminiNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AskGeminiViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val geminiNotifier: GeminiNotifier,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AskGeminiUiState())
    val uiState: StateFlow<AskGeminiUiState> = _uiState.asStateFlow()

    fun onPromptTextChanged(text: String) {
        _uiState.update { it.copy(promptText = text) }
    }

    fun onSendClicked() {
        val prompt = _uiState.value.promptText
        if (prompt.isBlank()) {
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            geminiRepository.sendPrompt(prompt).fold(
                onSuccess = { replyText ->
                    _uiState.update { it.copy(replyText = replyText, isLoading = false) }
                    geminiNotifier.notify(replyText)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(errorMessage = error.message, isLoading = false)
                    }
                },
            )
        }
    }
}
```

- [ ] **Step 3: Write `AskGeminiScreen.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AskGeminiScreen(viewModel: AskGeminiViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.promptText,
                onValueChange = viewModel::onPromptTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ask Gemini") },
            )

            Button(
                onClick = viewModel::onSendClicked,
                enabled = !uiState.isLoading && uiState.promptText.isNotBlank(),
            ) {
                Text(if (uiState.isLoading) "Sending..." else "Send")
            }

            val errorMessage = uiState.errorMessage
            if (errorMessage != null) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
            } else if (uiState.replyText.isNotEmpty()) {
                Text(text = uiState.replyText)
            }
        }
    }
}
```

- [ ] **Step 4: Write `MainActivity.kt`**

```kotlin
package com.ameyapb.androidexp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.ameyapb.androidexp.ui.askgemini.AskGeminiScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            AskGeminiScreen()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (!alreadyGranted) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
```

- [ ] **Step 5: Write `FakeGeminiRepository.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.gemini.GeminiRepository

class FakeGeminiRepository : GeminiRepository {
    var result: Result<String> = Result.success("fake reply")

    override suspend fun sendPrompt(prompt: String): Result<String> = result
}
```

- [ ] **Step 6: Write `FakeGeminiNotifier.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import com.ameyapb.androidexp.data.notification.GeminiNotifier

class FakeGeminiNotifier : GeminiNotifier {
    val notifiedReplies = mutableListOf<String>()

    override fun notify(replyText: String) {
        notifiedReplies.add(replyText)
    }
}
```

- [ ] **Step 7: Write `AskGeminiViewModelTest.kt`**

```kotlin
package com.ameyapb.androidexp.ui.askgemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskGeminiViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeGeminiRepository: FakeGeminiRepository
    private lateinit var fakeGeminiNotifier: FakeGeminiNotifier
    private lateinit var viewModel: AskGeminiViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeGeminiRepository = FakeGeminiRepository()
        fakeGeminiNotifier = FakeGeminiNotifier()
        viewModel = AskGeminiViewModel(fakeGeminiRepository, fakeGeminiNotifier)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onPromptTextChanged updates the prompt text`() {
        viewModel.onPromptTextChanged("why is the sky blue")

        assertEquals("why is the sky blue", viewModel.uiState.value.promptText)
    }

    @Test
    fun `onSendClicked on success updates reply text and notifies`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        fakeGeminiRepository.result = Result.success("because of Rayleigh scattering")

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("because of Rayleigh scattering", state.replyText)
        assertTrue(!state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(listOf("because of Rayleigh scattering"), fakeGeminiNotifier.notifiedReplies)
    }

    @Test
    fun `onSendClicked on failure sets error and does not notify`() = runTest {
        viewModel.onPromptTextChanged("why is the sky blue")
        fakeGeminiRepository.result = Result.failure(Exception("Gemini API error: bad key"))

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Gemini API error: bad key", state.errorMessage)
        assertTrue(!state.isLoading)
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }

    @Test
    fun `onSendClicked with a blank prompt does nothing`() = runTest {
        viewModel.onPromptTextChanged("   ")

        viewModel.onSendClicked()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(!viewModel.uiState.value.isLoading)
        assertTrue(fakeGeminiNotifier.notifiedReplies.isEmpty())
    }
}
```

- [ ] **Step 8: Run the ViewModel tests**

Run: `cd android-app && ./gradlew :app:testDebugUnitTest --tests "com.ameyapb.androidexp.ui.askgemini.AskGeminiViewModelTest"`
Expected: 4 tests pass.

- [ ] **Step 9: Commit**

```bash
git add android-app/app/src/main/java/com/ameyapb/androidexp/ui/ \
  android-app/app/src/main/java/com/ameyapb/androidexp/MainActivity.kt \
  android-app/app/src/test/java/com/ameyapb/androidexp/ui/
git commit -m "Add AskGemini screen, ViewModel, MainActivity, and ViewModel tests"
```

---

### Task 7: CI workflow

**Files:**
- Create: `.github/workflows/android-ci.yml`

- [ ] **Step 1: Write the workflow**

```yaml
name: Android CI

on:
  push:
    paths:
      - "android-app/**"
  pull_request:
    paths:
      - "android-app/**"

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: android-app
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build, lint, and test
        run: ./gradlew build lint testDebugUnitTest
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/android-ci.yml
git commit -m "Add Android CI workflow scoped to android-app"
```

---

### Task 8: Documentation updates

**Files:**
- Modify: `CLAUDE.md` (Architecture Overview section)
- Modify: `README.md`

- [ ] **Step 1: Append a native app subsection to `CLAUDE.md`'s Architecture Overview**

Document the new `android-app/` components: `GeminiApp`, `MainActivity`, `ui/askgemini/*`, `data/gemini/*`, `data/notification/*`, `di/AppModule`, matching the level of detail already used for `ask-gemini.js` in that section.

- [ ] **Step 2: Add an Android app setup section to `README.md`**

Cover: Android Studio, `local.properties` (copy from `local.properties.example`, set `GEMINI_API_KEY`), `./gradlew build`, `./gradlew testDebugUnitTest`, running on a device/emulator — alongside the existing Termux CLI instructions.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "Document the native Android app in CLAUDE.md and README"
```
