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
    implementation("dev.or2:all:3.0.4")
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

    // PostgreSQL storage
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:16.2.0"))
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

    /** `boot*` tasks serve one game stream; the revision set comes from PostgreSQL, not the args. */
    fun registerBootTask(name: String, cacheID: Int, gameType: String, environment: String, port: Int = 8090) {
        register<JavaExec>(name) {
            group = "application"
            description = "Serves $gameType ($environment) on port $port"
            mainClass.set("dev.openrune.MainKt")
            classpath = sourceSets["main"].runtimeClasspath
            args = listOf(cacheID.toString(), gameType, environment, port.toString())
            jvmArgs("-Xmx2G")
            workingDir = rootProject.projectDir
        }
    }

    registerBootTask("bootOldschool", 2727, "OLDSCHOOL", "LIVE")
    registerBootTask("bootRunescape", -1, "RUNESCAPE3", "LIVE", port = 8091)
    registerBootTask("bootSailing", -1, "OLDSCHOOL", "BETA", port = 8092)

    fun registerTool(name: String, mainClassName: String, taskDescription: String, heap: String = "4G") {
        register<JavaExec>(name) {
            group = "postgres"
            description = taskDescription
            mainClass.set(mainClassName)
            classpath = sourceSets["main"].runtimeClasspath
            jvmArgs("-Xmx$heap")
            workingDir = rootProject.projectDir
            if (project.hasProperty("toolArgs")) {
                args = project.property("toolArgs").toString().split(Regex("\\s+")).filter { it.isNotBlank() }
            }
        }
    }

    register<JavaExec>("runServer") {
        group = "application"
        description = "Run the server with explicit arguments: -PserverArgs=\"2649 OLDSCHOOL LIVE 8090\""
        mainClass.set("dev.openrune.MainKt")
        classpath = sourceSets["main"].runtimeClasspath
        jvmArgs("-Xmx2G")
        workingDir = rootProject.projectDir
        if (project.hasProperty("serverArgs")) {
            args = project.property("serverArgs").toString().split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }

    registerTool("ingestRevisions", "dev.openrune.tools.IngestMainKt", "Ingest revisions from OpenRS2 into PostgreSQL: -PtoolArgs=\"revs=240,241\"")
    registerTool("backfill", "dev.openrune.tools.BackfillMainKt", "Import older revisions newest first, yielding to new releases: -PtoolArgs=\"from=200 to=239\"", heap = "8G")
    registerTool("importLegacyBins", "dev.openrune.tools.ImportLegacyBinsMainKt", "Import legacy .bin revisions into PostgreSQL: -PtoolArgs=\"from=1 to=241\"", heap = "8G")
    registerTool("validateLegacy", "dev.openrune.tools.ValidateAgainstLegacyMainKt", "Compare PostgreSQL state and diffs against legacy .bin files: -PtoolArgs=\"revs=1,240,241\"", heap = "8G")
    registerTool("benchmark", "dev.openrune.tools.BenchmarkMainKt", "Benchmark the query paths: -PtoolArgs=\"revs=1,240,241\"", heap = "4G")
    registerTool("publishCdn", "dev.openrune.tools.PublishCdnMainKt", "Re-upload sprites / textures.zip / model .dat to the CDN: -PtoolArgs=\"revs=241 repair=true\"", heap = "8G")
    registerTool("renderSample", "dev.openrune.tools.RenderSampleMainKt", "Render a few item / object images to eyeball settings: -PtoolArgs=\"rev=241 items=4151\"", heap = "4G")
    registerTool("resetDatabase", "dev.openrune.tools.ResetDatabaseMainKt", "Drop all platform tables (destructive): -PtoolArgs=\"confirm=true\"", heap = "512M")
    registerTool("syntheticScale", "dev.openrune.tools.SyntheticScaleMainKt", "Generate an RS3-sized synthetic stream and measure: -PtoolArgs=\"revs=50 types=20 entities=100000\"")
}





