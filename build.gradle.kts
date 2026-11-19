plugins {
    id("com.android.library") version "9.3.1"
    `maven-publish`
}

group = "com.github.yonglemingyu"
version = "1.0.0"

android {
    namespace = "dev.yu.videoplayer.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    val media3 = "1.4.1"
    // 核心包对外暴露 MediaSource / DataSource，用 api 而非 implementation
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-datasource:$media3")
    api("androidx.media3:media3-exoplayer-hls:$media3")
    api("androidx.annotation:annotation:1.9.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.group.toString()
                artifactId = "yu_video_player_android"
                version = project.version.toString()
            }
        }
    }
}
