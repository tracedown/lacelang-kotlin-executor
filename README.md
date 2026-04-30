# lacelang-executor (Kotlin)

Reference Kotlin/JVM executor for [Lace](https://github.com/tracedown/lacelang) --
the reference implementation with **100% spec conformance** (v0.9.1, 178/178
conformance vectors). Runs `.lace` scripts against real HTTP endpoints and
emits ProbeResult JSON.

Parsing and semantic validation are delegated to
[`lacelang-kt-validator`](https://github.com/tracedown/lacelang-kt-validator) --
this package contains only the runtime (HTTP client, assertion evaluation, cookie
jars, extension dispatch). See `lace-spec.md` section 15 for the validator /
executor package separation rule.

## Installation

### From GitHub Releases

Download the shadow JARs for both the validator and executor:

```bash
# Download validator (required dependency)
curl -sL "https://github.com/tracedown/lacelang-kt-validator/releases/latest/download/lacelang-kt-validator-0.1.0-all.jar" \
    -o lacelang-kt-validator.jar

# Download executor
curl -sL "https://github.com/tracedown/lacelang-kt-executor/releases/latest/download/lacelang-kt-executor-0.1.0-all.jar" \
    -o lacelang-kt-executor.jar

# Run
java -jar lacelang-kt-executor.jar run script.lace --vars vars.json
```

The executor shadow JAR bundles the validator, so only the executor JAR
is needed for CLI usage.

### From source (composite build)

Clone both repos side by side and build:

```bash
git clone https://github.com/tracedown/lacelang-kt-validator.git
git clone https://github.com/tracedown/lacelang-kt-executor.git
cd lacelang-kt-executor
./gradlew shadowJar
```

The composite build declaration in `settings.gradle.kts` expects
`../lacelang-kt-validator` to exist.

## CLI

```bash
JAR=build/libs/lacelang-kt-executor-0.1.0-all.jar

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
| OkHttp | 4.12 | HTTP client with per-phase timing |
| Gson | 2.11 | JSON serialization |
| tomlj | 1.1.1 | TOML parsing (.laceext, lace.config) |

## Responsible use

This software is designed for monitoring endpoints you **own or have
explicit authorization to probe**. See `NOTICE` for the full statement.

## License

Apache License 2.0
