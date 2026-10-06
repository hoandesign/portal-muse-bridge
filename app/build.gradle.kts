import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val envProps = Properties().apply {
  val envFile = rootProject.file(".env")
  if (envFile.exists()) {
    envFile.bufferedReader().use { reader ->
      reader.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .forEach { line ->
          val parts = line.split("=", limit = 2)
          if (parts.size == 2) {
            setProperty(parts[0].trim(), parts[1].trim().trim('"', '\''))
          }
        }
    }
  }
  val localFile = rootProject.file("local.properties")
  if (localFile.exists()) {
    localFile.inputStream().use { load(it) }
  }
}

val museSdkToken = envProps.getProperty("MUSE_SDK_TOKEN", "")
val indexMcpToken = envProps.getProperty("INDEX_MCP_TOKEN", "")
val museSessionId = envProps.getProperty("MUSE_SESSION_ID", "")
val museApiUrl = envProps.getProperty("MUSE_API_URL", "https://api.muse.ai")

android {
  namespace = "com.portal.pebblebridge"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.portal.pebblebridge"
    minSdk = 28
    targetSdk = 29
    versionCode = 2
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "DEFAULT_MUSE_SDK_TOKEN", "\"$museSdkToken\"")
    buildConfigField("String", "DEFAULT_INDEX_MCP_TOKEN", "\"$indexMcpToken\"")
    buildConfigField("String", "DEFAULT_MUSE_SESSION_ID", "\"$museSessionId\"")
    buildConfigField("String", "DEFAULT_MUSE_API_URL", "\"$museApiUrl\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  lint {
    // Portal sideload target; Play Store is not the distribution channel.
    disable += "ExpiredTargetSdkVersion"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.okhttp)
  implementation(libs.bouncycastle)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.arch.core.testing)
  testImplementation(libs.robolectric)
  testImplementation("org.json:json:20240303")

  debugImplementation(libs.androidx.compose.ui.tooling)
}
