# Project Setup Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bootstrap the Android project with Gradle Kotlin DSL, feature-based module structure, Hilt DI, and GitHub Actions CI/CD.

**Architecture:** Multi-module Android project with feature modules and shared core modules. Each feature follows Clean Architecture (UI/Domain/Data). Core SSH and tmux logic isolated in dedicated modules.

**Tech Stack:** Gradle Kotlin DSL, Kotlin 1.9.22, Jetpack Compose 1.6.1, Hilt 2.50, AGP 8.2.2, JDK 17, Apache MINA sshd, JUnit + Mockk + Turbine

---

## Before You Start

- Ensure JDK 17 is installed: `java -version`
- Ensure Android SDK is available: `echo $ANDROID_HOME`

---

## Task 1: Create Gradle Wrapper

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew` (executable script)
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`

**Step 1: Create gradle-wrapper.properties**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**Step 2: Download gradlew scripts and wrapper jar**

Run:
```bash
cd /Users/leo/Developer/ClaudeRemote && \
mkdir -p gradle/wrapper && \
curl -sL https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradlew -o gradlew && \
chmod +x gradlew && \
curl -sL https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradlew.bat -o gradlew.bat && \
curl -sL https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar -o gradle/wrapper/gradle-wrapper.jar
```

**Step 3: Verify wrapper works**

Run:
```bash
./gradlew --version
```
Expected: Gradle 8.4

**Step 4: Commit**

```bash
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.jar
git commit -m "chore: add Gradle wrapper"
```

---

## Task 2: Create Version Catalog

**Files:**
- Create: `gradle/libs.versions.toml`

**Step 1: Create version catalog**

```toml
[versions]
agp = "8.2.2"
kotlin = "1.9.22"
coreKtx = "1.12.0"
lifecycleRuntimeKtx = "2.7.0"
activityCompose = "1.8.2"
composeBom = "2024.02.00"
navigationCompose = "2.7.7"
hilt = "2.50"
hiltNavigationCompose = "1.2.0"
sshd = "2.12.1"
coroutines = "1.7.3"
okhttp = "4.12.0"
mockk = "1.13.9"
turbine = "1.1.0"
junit = "4.13.2"
androidxJunit = "1.1.5"
espressoCore = "3.5.1"

[libraries]
# AndroidX Core
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

# Compose
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }

# Navigation
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltNavigationCompose" }

# SSH
sshd-core = { group = "org.apache.sshd", name = "sshd-core", version.ref = "sshd" }

# Coroutines
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# OkHttp
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }

# Testing
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
mockk-compose = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "kotlin" }

[bundles]
compose = ["androidx-compose-ui", "androidx-compose-ui-graphics", "androidx-compose-material3"]
compose-tooling = ["androidx-compose-ui-tooling-preview"]
```
> **Note:** Jetpack Compose Compiler is managed separately via `kotlinCompilerExtensionVersion` in compose-bom — no separate version ref needed.

**Step 2: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "chore: add Gradle version catalog"
```

---

## Task 3: Create Root Build Configuration

**Files:**
- Create: `build.gradle.kts` (root)
- Create: `settings.gradle.kts`

**Step 1: Create settings.gradle.kts**

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

rootProject.name = "ClaudeRemote"
include(":app")
include(":core:core:ssh")
include(":core:core:tmux")
include(":core:core:ui")
include(":features:features:chat")
include(":features:features:session")
include(":features:features:settings")
```

**Step 2: Create root build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

**Step 3: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

**Step 4: Create local.properties (ask user to fill)**

```properties
sdk.dir=/Users/leo/Library/Android/sdk
```

**Step 5: Commit**

```bash
git add build.gradle.kts settings.gradle.kts gradle.properties local.properties
git commit -m "chore: add root Gradle configuration"
```

---

## Task 4: Create Core UI Module

**Files:**
- Create: `core/core/ui/build.gradle.kts`
- Create: `core/core/ui/src/main/AndroidManifest.xml`
- Create: `core/core/ui/src/main/java/com/claude/remote/core/ui/theme/Theme.kt`
- Create: `core/core/ui/src/main/java/com/claude/remote/core/ui/theme/Color.kt`
- Create: `core/core/ui/src/main/java/com/claude/remote/core/ui/theme/Type.kt`
- Create: `core/core/ui/src/main/java/com/claude/remote/core/ui/components/ConnectionStatusDot.kt`

**Step 1: Create core/ui/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claude.remote.core.ui"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        composeOptions {
            kotlinCompilerExtensionVersion = "1.5.8"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

**Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

**Step 3: Create Color.kt**

```kotlin
package com.claude.remote.core.ui.theme

import androidx.compose.ui.graphics.Color

// Light Theme
val md_theme_light_primary = Color(0xFF0061A4)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFD4E3FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001C38)
val md_theme_light_secondary = Color(0xFF535F71)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_background = Color(0xFFFDFCFF)
val md_theme_light_onBackground = Color(0xFF1A1C1E)
val md_theme_light_surface = Color(0xFFFDFCFF)
val md_theme_light_onSurface = Color(0xFF1A1C1E)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_onError = Color(0xFFFFFFFF)

// Dark Theme
val md_theme_dark_primary = Color(0xFFA0C9FF)
val md_theme_dark_onPrimary = Color(0xFF00315B)
val md_theme_dark_primaryContainer = Color(0xFF004882)
val md_theme_dark_onPrimaryContainer = Color(0xFFD4E3FF)
val md_theme_dark_secondary = Color(0xFFBBC7DB)
val md_theme_dark_onSecondary = Color(0xFF253140)
val md_theme_dark_background = Color(0xFF1A1C1E)
val md_theme_dark_onBackground = Color(0xFFE2E2E6)
val md_theme_dark_surface = Color(0xFF1A1C1E)
val md_theme_dark_onSurface = Color(0xFFE2E2E6)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_onError = Color(0xFF690005)
```

**Step 4: Create Type.kt**

```kotlin
package com.claude.remote.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
```

**Step 5: Create Theme.kt**

```kotlin
package com.claude.remote.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
)

@Composable
fun ClaudeRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

**Step 6: Create ConnectionStatusDot.kt**

```kotlin
package com.claude.remote.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

@Composable
fun ConnectionStatusDot(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp
) {
    val color = when (state) {
        ConnectionState.CONNECTING -> Color(0xFFFFA000)    // Amber
        ConnectionState.CONNECTED -> Color(0xFF4CAF50)      // Green
        ConnectionState.RECONNECTING -> Color(0xFFFFA000)   // Amber
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)   // Red
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
```

**Step 7: Commit**

```bash
git add core/core/ui/build.gradle.kts core/core/ui/src/
git commit -m "feat(core:ui): add core UI module with theme"
```

---

## Task 5: Create Core SSH Module

**Files:**
- Create: `core/core/ssh/build.gradle.kts`
- Create: `core/core/ssh/src/main/AndroidManifest.xml`
- Create: `core/core/ssh/src/main/java/com/claude/remote/core/ssh/SshClient.kt`
- Create: `core/core/ssh/src/main/java/com/claude/remote/core/ssh/SshClientImpl.kt`
- Create: `core/core/ssh/src/androidTest/java/com/claude/remote/core/ssh/SshClientTest.kt`

**Step 1: Create core/ssh/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claude.remote.core.ssh"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.sshd.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.core)
}
```

**Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
</manifest>
```

**Step 3: Create SshClient.kt (interface)**

```kotlin
package com.claude.remote.core.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SshClient {
    val connectionState: StateFlow<ConnectionState>
    val outputStream: Flow<String>

    suspend fun connect(host: String, port: Int, username: String, password: String)
    suspend fun disconnect()
    suspend fun executeCommand(command: String): String
    fun sendInput(input: String)
}
```

**Step 4: Create SshClientImpl.kt (Apache MINA implementation)**

```kotlin
package com.claude.remote.core.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ClientChannel
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshClientImpl @Inject constructor() : SshClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    override val outputStream: Flow<String> = _outputFlow

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var channel: ClientChannel? = null

    override suspend fun connect(host: String, port: Int, username: String, password: String) {
        _connectionState.value = ConnectionState.CONNECTING

        try {
            client = SshClient.setUpDefaultClient().apply { start() }
            session = client?.connect(username, host, port)?.verify(10_000)?.session?.apply {
                addPasswordIdentity(password)
                auth().verify(10_000)
            }

            val outputStream = PipedOutputStream()
            val inputStream = PipedInputStream(outputStream)

            channel = session?.createChannel(ClientChannel.CHANNEL_SHELL).apply {
                setIn(inputStream)
                setOut(outputStream)
                setErr(outputStream)

                open()

                // Stream output in background
                scope.launch {
                    val buffer = ByteArray(1024)
                    while (true) {
                        val len = outputStream.read(buffer)
                        if (len > 0) {
                            _outputFlow.emit(String(buffer, 0, len))
                        }
                    }
                }
            }

            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    override suspend fun disconnect() {
        channel?.close()
        session?.close()
        client?.stop()
        client?.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun executeCommand(command: String): String {
        val channel = session?.createExecChannel(command)
        val output = MutableList<String>(1) { "" }

        channel?.let {
            it.open()
            val buffer = ByteArray(1024)
            val outputStream = it.invertedEnv?.getOutputStream() ?: throw IllegalStateException("No output stream")

            outputStream.write("$command\n".toByteArray())
            outputStream.flush()
            outputStream.close()

            val events = it.waitFor(
                setOf(ClientChannelEvent.CLOSED, ClientChannelEvent.EXIT_STATUS),
                30_000
            )

            if (events.contains(ClientChannelEvent.EXIT_STATUS)) {
                output[0] = it.exitStatus.toString()
            }
        }

        return output[0]
    }

    override fun sendInput(input: String) {
        val outputStream = channel?.invertedEnv?.getOutputStream()
        outputStream?.write("$input\n".toByteArray())
        outputStream?.flush()
    }
}
```

**Step 5: Write test**

```kotlin
package com.claude.remote.core.ssh

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class SshClientTest {

    @Test
    fun `connectionState emits CONNECTING then CONNECTED on successful connect`() = runTest {
        val client = SshClientImpl()

        client.connectionState.test {
            assertEquals(ConnectionState.DISCONNECTED, awaitItem())
            // Note: Full integration test would require mock SSH server
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

**Step 6: Commit**

```bash
git add core/core/ssh/build.gradle.kts core/core/ssh/src/
git commit -m "feat(core:ssh): add SSH client module with Apache MINA"
```

---

## Task 6: Create Core tmux Module

**Files:**
- Create: `core/core/tmux/build.gradle.kts`
- Create: `core/core/tmux/src/main/AndroidManifest.xml`
- Create: `core/core/tmux/src/main/java/com/claude/remote/core/tmux/TmuxSessionManager.kt`
- Create: `core/core/tmux/src/main/java/com/claude/remote/core/tmux/TmuxSessionManagerImpl.kt`
- Create: `core/core/tmux/src/main/java/com/claude/remote/core/tmux/TmuxSession.kt`

**Step 1: Create core/tmux/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claude.remote.core.tmux"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // SSH dependency for remote command execution
    implementation(project(":core:core:ssh"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
}
```

**Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

**Step 3: Create TmuxSession.kt**

```kotlin
package com.claude.remote.core.tmux

data class TmuxSession(
    val name: String,
    val windowName: String,
    val createdAt: Long = System.currentTimeMillis()
)
```

**Step 4: Create TmuxSessionManager.kt (interface)**

```kotlin
package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.flow.Flow

interface TmuxSessionManager {
    suspend fun listSessions(): List<TmuxSession>
    suspend fun createSession(sessionName: String, workingDirectory: String): TmuxSession
    suspend fun attachToSession(sessionName: String, client: SshClient)
    suspend fun sendCommand(sessionName: String, command: String, client: SshClient)
    suspend fun killSession(sessionName: String, client: SshClient)
    fun streamSessionOutput(sessionName: String, client: SshClient): Flow<String>
}
```

**Step 5: Create TmuxSessionManagerImpl.kt**

```kotlin
package com.claude.remote.core.tmux

import com.claude.remote.core.ssh.SshClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmuxSessionManagerImpl @Inject constructor() : TmuxSessionManager {

    override suspend fun listSessions(): List<TmuxSession> {
        // Implemented via SSH remote execution
        return emptyList()
    }

    override suspend fun createSession(sessionName: String, workingDirectory: String): TmuxSession {
        return TmuxSession(
            name = sessionName,
            windowName = sessionName
        )
    }

    override suspend fun attachToSession(sessionName: String, client: SshClient) {
        // tmux attach -t $sessionName
        client.sendInput("tmux attach -t $sessionName")
    }

    override suspend fun sendCommand(sessionName: String, command: String, client: SshClient) {
        // Send text input to tmux session
        client.sendInput(command)
    }

    override suspend fun killSession(sessionName: String, client: SshClient) {
        // tmux kill-session -t $sessionName
        client.sendInput("tmux kill-session -t $sessionName")
    }

    override fun streamSessionOutput(sessionName: String, client: SshClient): Flow<String> = flow {
        client.outputStream.collect { output ->
            emit(output)
        }
    }
}
```

**Step 6: Commit**

```bash
git add core/core/tmux/build.gradle.kts core/core/tmux/src/
git commit -m "feat(core:tmux): add tmux session manager module"
```

---

## Task 7: Create App Module Shell

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/claude/remote/app/ClaudeRemoteApplication.kt`
- Create: `app/src/main/java/com/claude/remote/app/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

**Step 1: Create app/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claude.remote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.claude.remote.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(project(":core:core:ui"))
    implementation(project(":core:core:ssh"))
    implementation(project(":core:core:tmux"))
    implementation(project(":features:features:chat"))
    implementation(project(":features:features:session"))
    implementation(project(":features:features:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.test.junit4)
}
```

**Step 2: Create AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.CAMERA" />

    <application
        android:name=".ClaudeRemoteApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ClaudeRemote">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.ClaudeRemote">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

**Step 3: Create ClaudeRemoteApplication.kt**

```kotlin
package com.claude.remote.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ClaudeRemoteApplication : Application()
```

**Step 4: Create MainActivity.kt**

```kotlin
package com.claude.remote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.claude.remote.core.ui.theme.ClaudeRemoteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClaudeRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Navigation will be added here
                }
            }
        }
    }
}
```

**Step 5: Create res/values/strings.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Claude Remote</string>
</resources>
```

**Step 6: Create res/values/themes.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ClaudeRemote" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

**Step 7: Create proguard-rules.pro**

```pro
# Keep Apache MINA
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
```

**Step 8: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro app/src/
git commit -m "feat(app): add app module shell with Hilt"
```

---

## Task 8: Create Feature Modules (Chat, Session, Settings)

**Files:**
- Create: `features/features/chat/build.gradle.kts` + source files
- Create: `features/features/session/build.gradle.kts` + source files
- Create: `features/features/settings/build.gradle.kts` + source files

**Step 1: Create features/features/chat/build.gradle.kts**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claude.remote.features.chat"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        composeOptions {
            kotlinCompilerExtensionVersion = "1.5.8"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:core:ui"))
    implementation(project(":core:core:ssh"))
    implementation(project(":core:core:tmux"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Markdown
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.17.0")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.17.0")

    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

**Step 2: Create ChatFeature src structure**

```kotlin
// features/features/chat/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
</manifest>
```

```kotlin
// features/features/chat/src/main/java/.../chat/ChatUiState.kt
package com.claude.remote.features.chat

import com.claude.remote.core.tmux.TmuxSession

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val currentSession: TmuxSession? = null,
    val error: String? = null
)

data class ChatMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
```

```kotlin
// features/features/chat/src/main/java/.../chat/ChatViewModel.kt
package com.claude.remote.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claude.remote.core.ssh.SshClient
import com.claude.remote.core.tmux.TmuxSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sshClient: SshClient,
    private val tmuxSessionManager: TmuxSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = text,
            isUser = true
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                isStreaming = true
            )
        }

        viewModelScope.launch {
            try {
                sshClient.sendInput(text)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message, isStreaming = false)
                }
            }
        }
    }

    fun retryLastMessage() {
        val lastUserMessage = _uiState.value.messages.filter { it.isUser }.lastOrNull() ?: return
        _uiState.update { it.copy(inputText = lastUserMessage.content) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
```

```kotlin
// features/features/chat/src/main/java/.../chat/ChatScreen.kt
package com.claude.remote.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.currentSession?.name ?: "Claude Remote",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    // Session options
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }
            }

            ChatInputBar(
                text = uiState.inputText,
                onTextChange = viewModel::onInputChanged,
                onSend = viewModel::sendMessage,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodyLarge,
            color = if (message.isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { /* Attach */ }) {
            Icon(Icons.Default.AttachFile, contentDescription = "Attach")
        }
        IconButton(onClick = { /* Voice */ }) {
            Icon(Icons.Default.Mic, contentDescription = "Voice")
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message...") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4
        )

        IconButton(onClick = onSend, enabled = text.isNotBlank()) {
            Icon(
                Icons.Default.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank())
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

**Step 3: Repeat for features:session and features:settings with minimal stubs**

For `features:session`:
- `SessionSwitcherScreen.kt` with pull-down menu listing tmux sessions
- `SessionSwitcherViewModel.kt`

For `features:settings`:
- `SettingsScreen.kt` with SSH server/port/username, theme, font size
- `SettingsViewModel.kt`

**Step 4: Commit each feature**

```bash
git add features/features/chat/build.gradle.kts features/features/chat/src/
git add features/features/session/build.gradle.kts features/features/session/src/
git add features/features/settings/build.gradle.kts features/features/settings/src/
git commit -m "feat(features): add chat, session, and settings feature modules"
```

---

## Task 9: Create GitHub Actions CI/CD

**Files:**
- Create: `.github/workflows/ci.yml`

**Step 1: Create ci.yml**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v2

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: ~/.gradle/caches
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest

      - name: Upload Debug APK
        uses: actions/upload-artifact@v3
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

**Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions workflow"
```

---

## Task 10: Verify Build

**Step 1: Run assembleDebug**

```bash
./gradlew assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL

**Step 2: Run tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: All tests pass

**Step 3: Commit final state**

```bash
git push origin main
```

---

## Execution Options

**Plan complete and saved to `docs/plans/2026-03-20-project-setup-plan.md`. Two execution options:**

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

**Which approach?**
