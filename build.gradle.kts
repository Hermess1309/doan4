import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding

plugins {
    base
    alias(libs.plugins.spotless)
}

allprojects {
    group = "vn.edu.p2p"
    version = "0.1.0-SNAPSHOT"
}

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.launcher

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    dependencies {
        "testImplementation"(platform(junitBom))
        "testImplementation"(junitJupiter)
        "testRuntimeOnly"(junitLauncher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
    extensions.configure<SpotlessExtension> {
        lineEndings = LineEnding.UNIX
        java {
            target("src/**/*.java")
            importOrder()
            trimTrailingWhitespace()
            leadingTabsToSpaces(4)
            endWithNewline()
        }
    }
}

spotless {
    lineEndings = LineEnding.UNIX
    format("projectFiles") {
        target("*.kts", "*.md", "*.properties", ".gitignore", ".gitattributes",
            "gradle/**/*.toml", "gradle/**/*.properties", "docs/*.md", ".github/workflows/*.yml",
            "*/build.gradle.kts")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}
tasks.named("clean") {
    dependsOn(subprojects.map { "${it.path}:clean" })
}
