plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

group = providers.environmentVariable("GROUP").orElse("io.github.xheghun").get()
version = providers.environmentVariable("VERSION").orElse("0.1.2").get()

kotlin {
    explicitApi()
}

android {
    namespace = "com.xheghun.devicetrust"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { cppFlags += "-std=c++20 -fvisibility=hidden" } }
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "device-trust"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name = "DeviceTrust"
                description = "Layered Android device-risk signals for root, hooking, emulators, and system integrity."
                url = "https://github.com/Xheghun/DeviceTrust"
                licenses {
                    license {
                        name = "Apache License 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer { id = "Xheghun"; name = "DeviceTrust contributors" }
                }
                scm {
                    connection = "scm:git:https://github.com/Xheghun/DeviceTrust.git"
                    developerConnection = "scm:git:ssh://git@github.com/Xheghun/DeviceTrust.git"
                    url = "https://github.com/Xheghun/DeviceTrust"
                }
            }
        }
    }
    repositories {
        maven {
            name = "localBuild"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
