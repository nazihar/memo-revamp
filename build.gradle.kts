plugins {
    id("memo.sbom")
}

// First-party group: SBOM tooling and the licence gate treat com.memo components as our own code.
allprojects {
    group = "com.memo"
}
