plugins {
    `kotlin-dsl`
}

dependencies {
    // Provided by the Android Gradle plugin applied in the consuming build.
    compileOnly("com.android.tools.build:gradle-api:8.9.3")
    compileOnly("org.ow2.asm:asm:9.7.1")
}

gradlePlugin {
    plugins {
        create("androidRegexCompat") {
            id = "starlightdiscord.android-regex-compat"
            implementationClass = "dev.mooner.starlightdiscord.buildlogic.AndroidRegexCompatPlugin"
        }
    }
}
