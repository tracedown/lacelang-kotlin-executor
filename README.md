# lacelang-executor (Kotlin)

[![Maven Central](https://img.shields.io/maven-central/v/dev.lacelang/lacelang-kotlin-executor)](https://central.sonatype.com/artifact/dev.lacelang/lacelang-kotlin-executor)

Reference Kotlin/JVM executor for [Lace](https://github.com/tracedown/lacelang) --
the reference implementation with **100% spec conformance**. Runs `.lace` scripts against real HTTP endpoints and
emits ProbeResult JSON.

Parsing and semantic validation are delegated to
[`dev.lacelang:kotlin-validator`](https://github.com/tracedown/lacelang-kotlin-validator) --
this package contains only the runtime (HTTP client, assertion evaluation, cookie
jars, extension dispatch). See `lace-spec.md` section 15 for the validator /
executor package separation rule.

## Installation

### From Maven Central

```kotlin
dependencies {
    implementation("dev.lacelang:lacelang-kotlin-executor:<version>")
}
```

The executor depends on `dev.lacelang:kotlin-validator`, which Gradle
resolves transitively.

### From GitHub Releases

Every release ships a shadow JAR that bundles the validator, so only the
executor JAR is needed for CLI usage:

```bash
curl -sL "https://github.com/tracedown/lacelang-kotlin-executor/releases/latest/download/lacelang-kt-executor-all.jar" \
    -o lacelang-kt-executor.jar

java -jar lacelang-kt-executor.jar run script.lace --vars vars.json
```

(The validator publishes its own `lacelang-kt-validator-all.jar` at
[lacelang-kotlin-validator/releases](https://github.com/tracedown/lacelang-kotlin-validator/releases)
for validate-only use.)

### From source

```bash
git clone https://github.com/tracedown/lacelang-kotlin-executor.git
cd lacelang-kotlin-executor
./gradlew shadowJar        # -> build/libs/lacelang-kt-executor-all.jar
```

The validator is a regular dependency (`dev.lacelang:kotlin-validator`,
resolved from `mavenLocal()` then Maven Central) — there is no composite
build. To build against unreleased validator changes, run
`./gradlew publishToMavenLocal` in the validator checkout first.

## CLI

```bash
JAR=build/libs/lacelang-kt-executor-all.jar

# Parse (delegates to validator)
java -jar $JAR parse script.lace

# Validate (delegates to validator)
java -jar $JAR validate script.lace --vars-list vars.json --context context.json

# Run -- full HTTP execution
java -jar $JAR run script.lace \
    --vars vars.json \
    --prev prev.json
```

All subcommands support `--pretty` for indented JSON.

## Library

The primary interface is the CLI. Library API exists for embedding in
Kotlin/JVM applications:

```kotlin
import dev.lacelang.executor.runScript
import dev.lacelang.executor.loadConfig
import dev.lacelang.validator.parse

val ast = parse("""get("https://example.com").expect(status: 200)""")
val config = loadConfig()
val result = runScript(ast, scriptVars = mapOf("key" to "val"), config = config)
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| OkHttp | 5.5 | HTTP client with per-phase timing |
| Gson | 2.11 | JSON serialization |
| tomlj | 1.1.1 | TOML parsing (.laceext, lace.config) |

## Responsible use

This software is designed for monitoring endpoints you **own or have
explicit authorization to probe**. See `NOTICE` for the full statement.

## License

Apache License 2.0
