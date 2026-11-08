import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("io.github.mooner1022.slplugin")
    // Strips regex flags Android rejects from library bytecode (JDA's Checks fails to initialize otherwise).
    id("starlightdiscord.android-regex-compat")
}

android {
    namespace = "dev.mooner.starlightdiscord"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mooner.starlightdiscord"
        minSdk = 26
        targetSdk = 33
        versionCode = 6
        // The plugin version in the generated starlight.json.
        versionName = "0.5.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            // Keeps META-INF/services (SLF4J provider) and okhttp3/internal/publicsuffix, which are needed at runtime.
            excludes += listOf("META-INF/*", "META-INF/versions/**", "META-INF/native-image/**", "META-INF/rewrite/**", "META-INF/maven/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    // Generates assets/starlight.json; the version is versionName.
    manifest {
        id = "discord"
        name = "Discord"
        mainClass = "dev.mooner.starlightdiscord.StarlightDiscordPlugin"
        // Hides the host's OkHttp 4 from JDA, which needs the OkHttp 5 this plugin ships.
        customClassLoader = "dev.mooner.starlightdiscord.loader.HostIsolatingClassLoader"
        // Worked out from the code, it would be 1.0.1, the oldest release PluginCore's API table records; the code
        // needs nothing newer than 1.0.0, so StarLight 1.0.0 keeps loading the plugin. slplugin checks it either way.
        apiVersion = "1.0.0"
        authors.add("mooner")
        description = "Discord client implementation for StarLight"
        category = "messenger"
    }
    deploy {
        // Pass -Pstarlight.device=<serial or ip:port> or set STARLIGHT_DEVICE.
        // Restarting force-stops StarLight on the device; opt in per run with -Pstarlight.restartApp=true.
    }
}

dependencies {
    // Provided by StarLight at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly("androidx.core:core-ktx:1.16.0")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("com.google.android.material:material:1.12.0")

    implementation("net.dv8tion:JDA:6.7.0") {
        // Voice only. opus-java ships desktop natives through JNA, and tink (with protobuf) encrypts voice packets.
        exclude(group = "club.minnced", module = "opus-java")
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
}
