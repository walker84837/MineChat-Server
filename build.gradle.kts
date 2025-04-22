import java.text.SimpleDateFormat
import java.util.*

plugins {
    id("com.gradleup.shadow") version "8.3.6"
    kotlin("jvm") version "2.1.10"
}

group = "org.winlogon.minechat"

fun getLatestGitTag(): String? {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor()
        if (process.exitValue() == 0) {
            process.inputStream.bufferedReader().readText().trim()
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

val shortVersion: String? = if (project.hasProperty("ver")) {
    project.property("ver").toString()
} else {
    getLatestGitTag()
}

val version: String = when {
    shortVersion.isNullOrEmpty() -> "0.0.0-SNAPSHOT"
    shortVersion.contains("-RC-") -> shortVersion.substringBefore("-RC-") + "-SNAPSHOT"
    else -> if (shortVersion.startsWith("v")) {
        shortVersion.substring(1).uppercase()
    } else {
        shortVersion.uppercase()
    }
}

val pluginName = rootProject.name
val pluginVersion = version
val pluginPackage = project.group.toString()
val projectName = rootProject.name

repositories {
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
        content {
            includeModule("io.papermc.paper", "paper-api")
            includeModule("io.papermc", "paperlib")
            includeModule("net.md-5", "bungeecord-chat")
        }
    }
    maven {
        name = "minecraft"
        url = uri("https://libraries.minecraft.net")
        content {
            includeModule("com.mojang", "brigadier")
        }
    }

    maven {
        url = uri("https://libraries.minecraft.net")
    }

    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.19.0")
    compileOnly("com.mojang:brigadier:1.1.8")
    compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.0")
    
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    filesMatching("**/paper-plugin.yml") {
        expand(
            "NAME" to pluginName,
            "VERSION" to pluginVersion,
            "PACKAGE" to pluginPackage
        )
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("io.papermc.lib", "shadow.io.papermc.paperlib")
    minimize()
}

// Disable jar and replace with shadowJar
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Utility tasks
tasks.register("printProjectName") {
    doLast {
        println(projectName)
    }
}

var shadowJarTask = tasks.shadowJar.get()
tasks.register("release") {
    dependsOn(tasks.build)
    doLast {
        if (!version.endsWith("-SNAPSHOT")) {
            shadowJarTask.archiveFile.get().asFile.renameTo(
                file("${layout.buildDirectory.get()}/libs/${rootProject.name}.jar")
            )
        }
    }
}
