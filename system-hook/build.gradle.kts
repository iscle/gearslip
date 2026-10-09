import java.util.Properties

plugins { alias(libs.plugins.android.application) }

val signing = Properties().apply {
    listOf(
        rootProject.file("keystore.properties"),
        rootProject.file("gearslip/keystore.properties")
    )
        .firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("system-hook"))

android {
    namespace = "app.seb3thehacker.gearslip.systemhook"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.seb3thehacker.gearslip.systemhook"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { signingConfigs.findByName("release")?.let { signingConfig = it } }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2"
            )
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

android.sourceSets {
    getByName("test").kotlin.srcDir("src/sharedTest/java")
    getByName("androidTest").kotlin.srcDir("src/sharedTest/java")
}

dependencies {
    compileOnly(libs.xposed.api)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.car.app)
    testImplementation(libs.xposed.api)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.car.app)
}
