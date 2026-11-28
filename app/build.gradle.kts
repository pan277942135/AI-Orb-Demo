import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.imageio.ImageIO

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aiorbdemo.vxkrmq"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/waterWorldAssets").get().asFile)
  sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/tab2PerimeterAssets").get().asFile)
}

// Assemble debug assets from the byte-frozen V2 archive and the checked-in
// non-destructive Work 1.5 geometry. The master PNG is deliberately excluded.
val waterWorldArchive = rootProject.file("releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip")
val waterWorldScene = rootProject.file("releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
val waterWorldWork3Motion = rootProject.file("releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION")
val prepareWaterWorldAssets = tasks.register<Copy>("prepareWaterWorldAssets") {
  into(layout.buildDirectory.dir("generated/waterWorldAssets/water_world_v2_1"))
  from(zipTree(waterWorldArchive)) {
    include("WATER_WORLD_V2/**")
    eachFile { path = path.removePrefix("WATER_WORLD_V2/").let { "01_REUSED_ASSETS/$it" } }
    includeEmptyDirs = false
  }
  from(waterWorldScene) {
    include("02_SUPPLEMENTED_ASSETS/**", "03_SCENE_GRAPH/**")
  }
  from(waterWorldWork3Motion) {
    include("work3_motion_profile.json")
    into("04_MOTION")
  }
}
val verifyFrozenWaterWorldArchive = tasks.register<Exec>("verifyFrozenWaterWorldArchive") {
  workingDir(rootProject.projectDir)
  commandLine("python3", "tools/water-world-scene-assembly/verify_android_asset_plan.py")
}
prepareWaterWorldAssets.configure { dependsOn(verifyFrozenWaterWorldArchive) }

// Package only the production V2 perimeter textures and their small runtime descriptors.
// The ZIP remains the immutable source of truth; the master, CPU previews and duplicate
// reference images are deliberately excluded from the APK.
val tab2PerimeterArchive = rootProject.file("docs/tab2/assets/TAB2_PERIMETER_HIFI_REFERENCE_ASSETS_V2.zip")
val tab2PerimeterMaster = rootProject.file("docs/tab2/assets/TAB2_P0_HIGH_FIDELITY_MASTER_V1.png")
val tab2PerimeterOutput = layout.buildDirectory.dir("generated/tab2PerimeterAssets")
val tab2PerimeterInputFiles = listOf(
  "textures/L1_RGBA.png",
  "textures/L1_NORMAL_RGB.png",
  "textures/L1_FLOW_RG.png",
  "textures/L2_RGBA.png",
  "textures/L2_NORMAL_RGB.png",
  "textures/L2_FLOW_RG.png",
  "textures/L3_RGBA.png",
  "textures/L3_NORMAL_RGB.png",
  "textures/L3_FLOW_RG.png",
  "textures/L4_DROPLET_ATLAS_RGBA.png",
  "geometry/CLOSED_ARC_LENGTH_LUT.json",
  "motion/KF01_KF06.json",
)
val prepareTab2PerimeterAssets = tasks.register("prepareTab2PerimeterAssets") {
  inputs.file(tab2PerimeterArchive)
  inputs.file(tab2PerimeterMaster)
  outputs.dir(tab2PerimeterOutput)
  doLast {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }

    val expectedArchiveSha = "f724393fa8254b9d18543747ec356ded8a6e91dd61311d72a9a5028d174b0004"
    val expectedMasterSha = "a37819bba6b144ca7fec27ace55e5b319c41e67b89a11e70d51200bce8749d67"
    val archiveBytes = tab2PerimeterArchive.readBytes()
    val masterBytes = tab2PerimeterMaster.readBytes()
    check(sha256(archiveBytes) == expectedArchiveSha) {
      "TAB2_V25_RESOURCE_ZIP_SHA_MISMATCH: expected=$expectedArchiveSha actual=${sha256(archiveBytes)}"
    }
    check(sha256(masterBytes) == expectedMasterSha) {
      "TAB2_V25_MASTER_SHA_MISMATCH: expected=$expectedMasterSha actual=${sha256(masterBytes)}"
    }

    val outputRoot = tab2PerimeterOutput.get().asFile
    outputRoot.deleteRecursively()
    val files = linkedMapOf<String, Any>()
    ZipFile(tab2PerimeterArchive).use { zip ->
      for (entryName in tab2PerimeterInputFiles) {
        val entry = zip.getEntry(entryName)
          ?: error("TAB2_V25_RESOURCE_MISSING: $entryName")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        val descriptor = linkedMapOf<String, Any>(
          "asset_path" to "tab2_perimeter/$entryName",
          "sha256" to sha256(bytes),
          "size_bytes" to bytes.size,
        )
        if (entryName.endsWith(".png")) {
          val image = ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("TAB2_V25_PNG_DECODE_FAILED: $entryName")
          val expectedWidth = if (entryName == "textures/L4_DROPLET_ATLAS_RGBA.png") 512 else 2048
          val expectedHeight = if (entryName == "textures/L4_DROPLET_ATLAS_RGBA.png") 512 else 128
          val expectedChannels = if (entryName.contains("_RGBA")) "RGBA" else "RGB"
          check(image.width == expectedWidth && image.height == expectedHeight) {
            "TAB2_V25_PNG_DIMENSIONS_INVALID: $entryName ${image.width}x${image.height}, expected ${expectedWidth}x${expectedHeight}"
          }
          check(image.colorModel.hasAlpha() == (expectedChannels == "RGBA")) {
            "TAB2_V25_PNG_CHANNELS_INVALID: $entryName expected=$expectedChannels"
          }
          descriptor["width"] = image.width
          descriptor["height"] = image.height
          descriptor["channels"] = expectedChannels
        }
        val target = outputRoot.resolve("tab2_perimeter/$entryName")
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        files[entryName] = descriptor
      }
    }

    val motion = groovy.json.JsonSlurper().parseText(
      outputRoot.resolve("tab2_perimeter/motion/KF01_KF06.json").readText()
    ) as Map<*, *>
    val manifest = linkedMapOf<String, Any>(
      "schema" to "TAB2_PERIMETER_V25_ASSET_MANIFEST_V1",
      "repository" to "pan277942135/AI-Orb-Demo",
      "archive_sha256" to expectedArchiveSha,
      "master_sha256" to expectedMasterSha,
      "files" to files,
      "relative_speed" to (motion["relative_speed"] ?: error("TAB2_V25_MOTION_SPEEDS_MISSING")),
      "loop_contract" to "continuous_closed_perimeter_two_opposed_phases",
    )
    val manifestFile = outputRoot.resolve("tab2_perimeter/manifest.json")
    manifestFile.parentFile.mkdirs()
    manifestFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(manifest)))
  }
}

tasks.matching {
  (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
    it.name.contains("lint", ignoreCase = true) ||
    it.name == "preBuild" ||
    it.name.startsWith("test")
}.configureEach { dependsOn(prepareWaterWorldAssets) }
tasks.matching {
  (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
    it.name.contains("lint", ignoreCase = true) ||
    it.name == "preBuild" ||
    it.name.startsWith("test")
}.configureEach { dependsOn(prepareTab2PerimeterAssets) }

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  // implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  // implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.exifinterface)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  // debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
