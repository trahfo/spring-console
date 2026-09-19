plugins {
    java
    alias(libs.plugins.spring.boot)
}

description = "Example Spring Boot todo-list application"

sourceSets {
    val main = getByName("main")
    val test = getByName("test")
    create("consoleTest") {
        java {
            srcDir("src/consoleTest/java")
        }
        resources {
            srcDir("src/consoleTest/resources")
        }
        compileClasspath += main.output + test.output
        runtimeClasspath += main.output + test.output
    }
}

val consoleTestImplementation by configurations.getting {
    extendsFrom(configurations.getByName("testImplementation"))
}
val consoleTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation(project(":spring-console-starter"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Gives TestRestTemplate a request factory that supports PATCH
    testImplementation("org.apache.httpcomponents.client5:httpclient5")

    consoleTestImplementation(project(":spring-console-starter"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    // Real parameter names make console introspection (inspect_bean) and
    // Spring's reflection-based binding friendlier.
    options.compilerArgs.add("-parameters")
}

// When the React frontend has been built (cd frontend && npm run build),
// bundle it into the jar so the app serves the UI itself.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    from("frontend/dist") {
        into("BOOT-INF/classes/static")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val consoleTest = tasks.register<Test>("consoleTest") {
    description = "Runs the spring-console integration tests against the todo app."
    group = "verification"
    testClassesDirs = sourceSets["consoleTest"].output.classesDirs
    classpath = sourceSets["consoleTest"].runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.check {
    dependsOn(consoleTest)
}
