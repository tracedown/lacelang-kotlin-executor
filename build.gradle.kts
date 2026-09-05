plugins {
    kotlin("jvm") version "2.4.10"
    application
    id("com.gradleup.shadow") version "9.6.1"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

// group and version come from gradle.properties (single source of truth).

// mavenLocal first so a locally-published validator (e.g. an unreleased spec
// version) overrides the Central copy for local dev; CI without the local
// artifact falls through to Central, the release source of truth.
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("dev.lacelang:kotlin-validator:0.1.6")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    implementation("org.tomlj:tomlj:1.1.1")
    // tomlj's public API carries checkerframework TYPE_USE @Nullable annotations
    // but does not declare checker-qual, so the annotation class is off the
    // compile classpath. Kotlin 2.4 turns that from a warning into an error
    // ("Type annotation class ... is inaccessible"). Annotations only — not
    // needed at runtime.
    compileOnly("org.checkerframework:checker-qual:3.49.5")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
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
val generateVersionInfo = tasks.register("generateVersionInfo") {
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
val verifyManifestVersion = tasks.register("verifyManifestVersion") {
    // Captured at configuration time: Task.project is unavailable during
    // execution under the configuration cache (Gradle 10 makes it an error).
    val manifest = layout.projectDirectory.file("lace-executor.toml")
    val projectVersion = project.version.toString()
    inputs.file(manifest)
    inputs.property("version", projectVersion)
    doLast {
        val declared = Regex("""(?m)^version\s*=\s*"([^"]+)"""")
            .find(manifest.asFile.readText())?.groupValues?.get(1)
        require(declared == projectVersion) {
            "lace-executor.toml version ($declared) != project version ($projectVersion) — update lace-executor.toml."
        }
    }
}
tasks.named("check") { dependsOn(verifyManifestVersion) }

// Keep the fat CLI jar for the GitHub release, but never publish it to Maven
// Central — consumers resolve the thin jar and its POM dependencies.
(components["java"] as org.gradle.api.component.AdhocComponentWithVariants)
    .withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() }

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
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
