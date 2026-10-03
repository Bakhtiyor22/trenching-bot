plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    kotlin("kapt") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.trenching"
version = "0.0.1-SNAPSHOT"
description = "Solana paper-trading research system"

springBoot {
    mainClass.set("com.trenching.bot.TrenchingBotApplicationKt")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.mapstruct:mapstruct:1.6.3")
    kapt("org.mapstruct:mapstruct-processor:1.6.3")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val withConsole = providers.gradleProperty("withConsole").isPresent
val frontendDirectory = layout.projectDirectory.dir("frontend")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) {
    listOf("cmd", "/c", "npm.cmd")
} else {
    listOf("npm")
}

if (withConsole) {
    val consoleNpmCi = tasks.register<Exec>("consoleNpmCi") {
        description = "Install the console's locked frontend dependencies"
        group = "build"
        workingDir(frontendDirectory)
        commandLine(npmCommand + "ci")
        inputs.files(frontendDirectory.file("package.json"), frontendDirectory.file("package-lock.json"))
        outputs.dir(frontendDirectory.dir("node_modules"))
    }
    val consoleBuild = tasks.register<Exec>("consoleBuild") {
        description = "Build the console SPA for the application jar"
        group = "build"
        dependsOn(consoleNpmCi)
        workingDir(frontendDirectory)
        commandLine(npmCommand + listOf("run", "build"))
        inputs.files(fileTree(frontendDirectory) {
            exclude("node_modules/**", "dist/**", "*.tsbuildinfo", ".env", ".env.*", "npm-debug.log*")
        })
        outputs.dir(frontendDirectory.dir("dist"))
    }
    tasks.processResources {
        dependsOn(consoleBuild)
        from(frontendDirectory.dir("dist")) { into("static/console") }
    }
}

val cleanConsoleResources = tasks.register<Delete>("cleanConsoleResources") {
    delete(layout.buildDirectory.dir("resources/main/static/console"))
}

tasks.processResources {
    dependsOn(cleanConsoleResources)
    inputs.property("withConsole", withConsole)
}

tasks.withType<Test> {
    useJUnitPlatform()
    inputs.property("postgresProvider", providers.environmentVariable("TRENCHING_TESTS_POSTGRES_PROVIDER").orElse("docker"))
    inputs.property("postgresBinaryDirectory", providers.environmentVariable("TRENCHING_TESTS_POSTGRES_BINARY_DIRECTORY").orElse(""))
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("live") }
}

tasks.register<Test>("unitTest") {
    description = "Deterministic offline tests without PostgreSQL or live APIs"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    useJUnitPlatform { excludeTags("integration", "live") }
}

tasks.register<Test>("liveTest") {
    description = "Read-only connectivity smoke tests against public Solana devnet"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    useJUnitPlatform {
        includeTags("live")
    }
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("replay") {
    description = "Run a deterministic paper-trading fixture without Spring or PostgreSQL"
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.trenching.bot.replay.api.ReplayCommandKt")
    args(providers.gradleProperty("dataset").orElse("src/main/resources/replay/samples/weekly-buy-v1.json").get())
    args(providers.gradleProperty("report").orElse("build/replay-report.json").get())
}

tasks.register<JavaExec>("persistedReplay") {
    description = "Atomically persist a deterministic replay in the configured PostgreSQL database"
    group = "application"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.trenching.bot.replay.api.PersistedReplayCommandKt")
    systemProperty("spring.profiles.active", providers.gradleProperty("profile").orElse("local").get())
    args(providers.gradleProperty("dataset").orElse("src/main/resources/replay/samples/weekly-buy-v1.json").get())
    args(providers.gradleProperty("report").orElse("build/persisted-replay-report.json").get())
}

// `bootRun` explicitly selects the local profile for developer convenience.
tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun> {
    systemProperty("spring.profiles.active", "local")
}
