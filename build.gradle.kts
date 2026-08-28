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
    implementation(kotlin("reflect"))
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-server-compression:2.3.5")
    implementation("io.ktor:ktor-server-cors:2.3.5")
    implementation("io.ktor:ktor-serialization-gson:2.3.5")
    implementation("io.ktor:ktor-server-status-pages:2.3.5")
    implementation("dev.or2:all:2.4.16")
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

    // Zstd compression for diff binary format
    implementation("com.github.luben:zstd-jni:1.5.5-11")

    // Sprite CDN uploads (S3 → CloudFront)
    implementation(platform("software.amazon.awssdk:bom:2.25.60"))
    implementation("software.amazon.awssdk:s3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.openrune.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
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

    fun registerBootTask(name: String, cacheID : Int,gameType: String, environment: String) {
        register<JavaExec>(name) {
            group = "application"
            description = "Boots the RuneScape cache with $gameType ($environment)"
            mainClass.set("dev.openrune.MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            args = listOf(cacheID.toString(),gameType, environment)
            jvmArgs("-Xmx8G")
        }
    }

    fun registerBootTaskDev(name: String, cacheID : Int,gameType: String, environment: String) {
        register<JavaExec>(name) {
            group = "application"
            description = "Boots the RuneScape cache with $gameType ($environment)"
            mainClass.set("dev.openrune.MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            args = listOf(cacheID.toString(),gameType, environment)
            jvmArgs("-Xmx8G","-Dopenrune.perf.logs=true","-Dopenrune.table.logs=true")
        }
    }

    registerBootTask("bootRunescape",    -1,   "RUNESCAPE3", "LIVE")
    registerBootTask("bootOldschool",    2649, "OLDSCHOOL",   "LIVE")
    registerBootTaskDev("bootOldschoolDev", 2649,   "OLDSCHOOL",   "DEV")
    registerBootTask("bootSailing",      -1,   "OLDSCHOOL",   "BETA")

    register<JavaExec>("runDownloadAllCaches") {
        group = "application"
        description = "Download and unzip all OSRS caches (rev 1 to latest) one by one for later dumping"
        mainClass.set("dev.openrune.DownloadAllCachesMainKt")
        classpath = sourceSets["main"].runtimeClasspath
    }

    register<JavaExec>("runDumperMain") {
        group = "application"
        description = "Runs the DiffDumper entrypoint in DumperMainKt"
        mainClass.set("dev.openrune.DumperMainKt")
        classpath = sourceSets["main"].runtimeClasspath
        jvmArgs("-Xmx4G")
    }

    register<JavaExec>("migrateSpritesToCdn") {
        group = "cdn"
        description =
            "Upload sprite PNGs from local .bin diffs to S3/R2 CDN (reconstructs full set per rev). " +
                "Props: -PcdnFrom=1 -PcdnTo=500 -PcdnDryRun=true -PcdnRepair=true -PcdnSkipUnchanged=true"
        mainClass.set("dev.openrune.MigrateSpritesToCdnMainKt")
        classpath = sourceSets["main"].runtimeClasspath
        jvmArgs("-Xmx8G")
        // Prefer working dir = project root so cache/ + .env resolve.
        workingDir = rootProject.projectDir

        val passProps = listOf(
            "cdnGame",
            "cdnEnv",
            "cdnFrom",
            "cdnTo",
            "cdnDryRun",
            "cdnSkipUnchanged",
            "cdnRepair",
        )
        for (key in passProps) {
            if (project.hasProperty(key)) {
                systemProperty(key, project.property(key).toString())
            }
        }
        // Also accept CLI args after -- e.g. gradlew migrateSpritesToCdn -- dryRun=true from=100
        if (project.hasProperty("cdnArgs")) {
            args = project.property("cdnArgs").toString().split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }
}





