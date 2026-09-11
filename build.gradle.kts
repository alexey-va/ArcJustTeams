plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "ru.ruscrafting"
version = "0.3.2"
description = "Native team hub and Lands/EliteMobs bridge for justTeams"

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content {
            includeGroup("ru.ruscrafting.arc")
            includeGroup("ru.ruscrafting.thirdparty")
        }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.6")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.6")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.6")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.6")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.6")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.6")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.github.angeschossen:LandsAPI:7.25.4")
    compileOnly("ru.ruscrafting.thirdparty:elitemobs-api:10.1.1")

    testImplementation(kotlin("test"))
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.6")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.6")
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test { useJUnitPlatform() }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
    }
    check { dependsOn(shadowJar) }
}
