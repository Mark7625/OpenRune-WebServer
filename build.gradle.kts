plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "8.1.1" // Add Shadow plugin
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://raw.githubusercontent.com/OpenRune/hosting/master")
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-server-core:2.3.2")

    implementation("dev.or2:all:2.2.9.1")
    // https://mvnrepository.com/artifact/it.unimi.dsi/fastutil
    implementation("it.unimi.dsi:fastutil:8.5.14")

    implementation("io.ktor:ktor-server-netty:2.3.2")
    implementation("io.ktor:ktor-client-cio:2.3.2") // Replace with the latest version
    implementation("io.ktor:ktor-server-auth:2.3.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.ktor:ktor-server-auth-jwt:2.3.2")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.2")
    implementation("net.java.dev.jna:jna:5.10.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.2")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.mindrot:jbcrypt:0.4")
    testImplementation("io.ktor:ktor-server-tests:2.3.2")
    implementation("org.litote.kmongo:kmongo-coroutine:4.8.0")
    implementation("ch.qos.logback:logback-classic:1.4.5") // Add this line
    implementation("org.litote.kmongo:kmongo:4.8.0")
    implementation("io.ktor:ktor-server-call-logging:2.1.0")
    implementation("io.ktor:ktor-server-cors:2.3.3")
    // https://mvnrepository.com/artifact/com.google.code.gson/gson
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.github.microutils:kotlin-logging:1.12.5")
    implementation("me.tongfei:progressbar:0.9.5")
    implementation("cc.ekblad:4koma:1.2.2-openrune")
}

tasks {
    shadowJar {
        archiveBaseName.set("app")
        archiveClassifier.set("") // Remove the `-all` classifier
        archiveVersion.set(version.toString())
        mergeServiceFiles() // Merge service files if needed
        manifest {
            attributes(
                "Main-Class" to "MainKt" // Replace with your main class
            )
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

tasks {
    fun registerBootTask(name: String, rev: Int, gameType: String, environment: String) {
        register<JavaExec>(name) {
            group = "runescape"
            description = "Boots the RuneScape cache with $gameType ($environment)"
            mainClass.set("MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            args = listOf(rev.toString(), gameType, environment)
            jvmArgs("-Xmx4G")
        }
    }

    registerBootTask("bootRunescape", -1, "RUNESCAPE", "LIVE")

    registerBootTask("bootOldschool", 235, "OLDSCHOOL", "LIVE")

    registerBootTask("bootSailing", 232, "OLDSCHOOL", "BETA")
}