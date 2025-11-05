plugins {
    kotlin("jvm") version "1.9.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.openrune"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://raw.githubusercontent.com/OpenRune/hosting/master")
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-gson:2.3.5")
    implementation("io.ktor:ktor-server-status-pages:2.3.5")
    implementation("dev.or2:all:2.2.9.1")
    implementation("cc.ekblad:4koma:1.2.2-openrune")

    // JSON serialization with Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // For async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Progress bar (cross-platform, works on Linux)
    implementation("me.tongfei:progressbar:0.9.5")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.openrune.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        archiveBaseName.set("openrune-server")
        archiveClassifier.set("")
        archiveVersion.set("")
        manifest {
            attributes(mapOf("Main-Class" to "dev.openrune.MainKt"))
        }
        // Merge service files (e.g., for SLF4J)
        mergeServiceFiles()
    }
    
    // Hide the default "run" task
    named<JavaExec>("run") {
        isEnabled = false
        group = null
    }

    fun registerBootTask(name: String, rev: Int, gameType: String, environment: String) {
        register<JavaExec>(name) {
            group = "application"
            description = "Boots the RuneScape cache with $gameType ($environment)"
            mainClass.set("dev.openrune.MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            args = listOf(rev.toString(), gameType, environment)
            jvmArgs("-Xmx4G")
        }
    }

    registerBootTask("bootRunescape", -1, "RUNESCAPE3", "LIVE")
    registerBootTask("bootOldschool", 235, "OLDSCHOOL", "LIVE")
    registerBootTask("bootSailing", 232, "OLDSCHOOL", "BETA")
}





