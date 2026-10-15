plugins {
    id("memo.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    "implementation"(platform(libs.spring.boot.dependencies))
    "implementation"(platform(libs.spring.modulith.bom))
    "testImplementation"(platform(libs.spring.boot.dependencies))
    "testImplementation"(platform(libs.spring.modulith.bom))
    "developmentOnly"(platform(libs.spring.boot.dependencies))
}
