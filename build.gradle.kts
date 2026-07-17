import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// group and version come from gradle.properties (single source of truth).

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.lacelang:kotlin-validator:0.1.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    implementation("org.tomlj:tomlj:1.1.1")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("dev.lacelang.executor.CliKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

// ── Version single-source ──
// The version lives only in gradle.properties. Generate the runtime VERSION
// constant from it so the CLI banner and probe User-Agent can never drift.
val generateVersionInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("dev/lacelang/executor/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "// Generated from the project version (gradle.properties) — do not edit.\n" +
                "package dev.lacelang.executor\n\nconst val VERSION = \"$ver\"\n",
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateVersionInfo) }

// Name the fat jar without a version so lace-executor.toml and the release
// workflow reference it by a stable path.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("lacelang-kt-executor-all.jar")
}

// Fail the build if the Lace manifest's version drifts from the project version.
val verifyManifestVersion by tasks.registering {
    doLast {
        val declared = Regex("""(?m)^version\s*=\s*"([^"]+)"""")
            .find(file("lace-executor.toml").readText())?.groupValues?.get(1)
        require(declared == project.version.toString()) {
            "lace-executor.toml version ($declared) != project version (${project.version}) — update lace-executor.toml."
        }
    }
}
tasks.named("check") { dependsOn(verifyManifestVersion) }

// Keep the fat CLI jar for the GitHub release, but never publish it to Maven
// Central — consumers resolve the thin jar and its POM dependencies.
(components["java"] as org.gradle.api.component.AdhocComponentWithVariants)
    .withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("dev.lacelang", "lacelang-kotlin-executor", version.toString())

    pom {
        name.set("Lace Kotlin Executor")
        description.set(
            "Kotlin executor for the Lace probe scripting language — HTTP runtime, assertion evaluation, and extension dispatch.",
        )
        inceptionYear.set("2026")
        url.set("https://lacelang.dev")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("lacelang")
                name.set("Lace")
                url.set("https://lacelang.dev")
            }
        }
        scm {
            url.set("https://github.com/tracedown/lacelang-kotlin-executor")
            connection.set("scm:git:https://github.com/tracedown/lacelang-kotlin-executor.git")
            developerConnection.set("scm:git:ssh://git@github.com/tracedown/lacelang-kotlin-executor.git")
        }
    }
}
