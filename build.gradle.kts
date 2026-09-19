plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "io.github.springconsole"
    version = (findProperty("version") as? String)?.takeIf { it != "unspecified" } ?: "0.1.0-SNAPSHOT"
}
