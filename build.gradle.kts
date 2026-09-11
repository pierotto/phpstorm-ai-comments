import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "cz.petrgala"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        phpstorm("2024.1.7")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_1_9
        languageVersion = KotlinVersion.KOTLIN_1_9
        // Without this Kotlin 1.9 emits delegating stubs for ToolWindowFactory defaults that the plugin verifier reports as internal API use.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "cz.petrgala.aicomments"
        name = "AI Comments"
        version = project.version.toString()
        description = "Per-line AI comments stored in .claude/comments.json and processed by Claude Code."
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PhpStorm, "2024.1.7")
            create(IntelliJPlatformType.PhpStorm, "2026.2.2")
        }
    }
}

tasks {
    // IJPGP 2.18.1 derives the target from the toolchain (intellij-platform-gradle-plugin#1772), so pin 17 here.
    withType<JavaCompile> {
        options.release.set(17)
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    test {
        useJUnitPlatform()
    }
    prepareTestSandbox {
        // The bundled Swagger plugin ships a postStartupActivity that references
        // com.intellij.swagger.visualEditing.SwVisualEditingActionsTestService, a class
        // missing from the PhpStorm 2024.1.7 release build of swagger.jar. It runs
        // unconditionally on project open and crashes every BasePlatformTestCase.
        disabledPlugins.add("com.intellij.swagger")
    }
}
