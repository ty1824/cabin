plugins {
    kotlin("jvm") version "2.0.0"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
}

group = "dev.dialector"
version = "1.0-SNAPSHOT"

val junit_version = "5.7.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junit_version")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junit_version")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
}
