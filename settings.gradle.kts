rootProject.name = "lacelang-kt-executor"

// Composite build: look for the validator as a sibling directory (local dev)
// or as a subdirectory (CI checkout).
val validatorDir = listOf(
    file("../lacelang-kt-validator"),
    file("lacelang-kt-validator"),
).firstOrNull { it.isDirectory }

if (validatorDir != null) {
    includeBuild(validatorDir)
}
