plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    `java-library`
    `maven-publish`
    `signing`
}

description = "Spring Console — an interactive Kotlin REPL and MCP server for Spring Boot applications"

dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    api("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation(libs.jline)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Kotlin scripting / REPL engine
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.compiler.embeddable)

    // Optional integrations — only active when present on the host application's classpath
    compileOnly("jakarta.persistence:jakarta.persistence-api")
    compileOnly("org.springframework.data:spring-data-commons")
    compileOnly("org.springframework.data:spring-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
    testImplementation("com.h2database:h2")
    testImplementation(kotlin("test"))
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    repositories {
        maven {
            name = "Staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "spring-console"
            pom {
                name.set("spring-console")
                description.set(project.description)
                url.set("https://github.com/trahfo/spring-console")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("trahfo")
                        name.set("Jakob Sommerhuber")
                        url.set("https://github.com/trahfo")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/trahfo/spring-console.git")
                    developerConnection.set("scm:git:ssh://github.com:trahfo/spring-console.git")
                    url.set("https://github.com/trahfo/spring-console")
                }
            }
        }
    }
}

signing {
    val signingKey = System.getenv("SIGNING_KEY") ?: (findProperty("signingKey") as? String)
    val signingPassword = System.getenv("SIGNING_PASSWORD") ?: (findProperty("signingPassword") as? String)
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword ?: "")
        sign(publishing.publications["maven"])
    }
}

val bundleCentralZip = tasks.register<Zip>("bundleCentralZip") {
    dependsOn("publishMavenPublicationToStagingRepository")
    from(layout.buildDirectory.dir("staging-deploy"))
    archiveFileName.set("bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
}

