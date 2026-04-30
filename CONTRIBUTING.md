# Contributing to lacelang-executor (Kotlin)

The reference Kotlin executor for [Lace](https://github.com/tracedown/lacelang)
is an open-source project under the Apache License 2.0. Contributions are
welcome -- bug fixes, new features, test coverage, and documentation.

## Relationship to the spec

This executor implements the Lace specification defined in the
[lacelang](https://github.com/tracedown/lacelang) repository. The spec
is the source of truth. If the executor's behaviour diverges from the
spec, that is a bug in the executor -- not in the spec.

Changes that require spec modifications (new syntax, new error codes,
changed semantics) must be proposed upstream in the spec repo first.

## Conventions

- **Naming**: camelCase for wire-format fields (matching the spec),
  camelCase for internal Kotlin identifiers.
- **No purely stylistic changes**: refactoring whitespace, rewording
  comments, or reformatting code without a functional reason will not
  be accepted.
- **Tests required**: every behaviour change must include tests.

## How to contribute

1. **Open an issue** if you want feedback before implementing. This is
   optional -- you can also go straight to a PR.
2. **Open a PR** with the proposed changes. Include:
   - The code change
   - Unit tests (in `src/test/`)
   - Updated README if the public API changes
3. Run the test suite before submitting:
   ```bash
   ./gradlew test
   ```
4. Discussion happens in PR comments. A maintainer must approve before
   merge.

## Test suite

| Suite | Command | Covers |
|-------|---------|--------|
| Unit | `./gradlew test` | Expression evaluator, extension DSL, config loader |
| Conformance | `lace-conformance -m lace-executor.toml` | Full spec compliance (178 vectors) |

## Version

This package tracks the spec version via `VERSION` in `Executor.kt`.
The package version is independent and follows its own release cadence.

## License

By contributing, you agree that your contributions will be licensed
under the Apache License 2.0, the same license as the project.
