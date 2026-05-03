import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    application
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.0"
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "1.9.20"
}

val pluginId = "dev.sweep.assistant"
val pluginName = "Sweep Autocomplete"
println("Building plugin: $pluginName with ID: $pluginId")
group = "dev.sweep"
version = "1.29.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

intellijPlatform {
    autoReload.set(false) // this triggers unloading which is very annoying
    buildSearchableOptions.set(false)
    pluginConfiguration {
        id.set(pluginId)
        name.set(pluginName)
    }

    pluginVerification {
        ides {
            select {
                types.set(listOf(IntelliJPlatformType.WebStorm))
                channels.set(listOf(ProductRelease.Channel.RELEASE))
                sinceBuild.set("242")
                untilBuild.set("243.*")
            }
//            select {
//                types.set(listOf(IntelliJPlatformType.DataGrip))
//                channels.set(listOf(ProductRelease.Channel.RELEASE))
//                sinceBuild.set("241") // 23x doesn't support vcs
//                untilBuild.set("243.*")
//            }
            select {
                types.set(
                    listOf(
                        IntelliJPlatformType.IntellijIdeaUltimate,
                        IntelliJPlatformType.IntellijIdeaCommunity,
                        IntelliJPlatformType.GoLand,
                        IntelliJPlatformType.PyCharmCommunity,
                        IntelliJPlatformType.PyCharmProfessional,
                        IntelliJPlatformType.CLion,
                        IntelliJPlatformType.Rider,
                        IntelliJPlatformType.AndroidStudio,
                        IntelliJPlatformType.RustRover,
                        IntelliJPlatformType.RubyMine,
                        IntelliJPlatformType.PhpStorm,
                    ),
                )
                channels.set(listOf(ProductRelease.Channel.RELEASE))
                sinceBuild.set("241")
                untilBuild.set(provider { null })
            }
        }
    }
}

tasks {
    // Need JDK 17 for Intellij 2024.1.7

    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("241")
        untilBuild.set(provider { null })
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    processResources {
        // Set duplicate strategy for all files
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

    }

    buildPlugin {
        archiveBaseName.set("sweepai")
    }

    runIde {
        jvmArgs("-Xmx2g") // Set maximum heap size
        jvmArgs("-XX:ReservedCodeCacheSize=512m") // Set code cache size
        jvmArgs("-XX:+UseG1GC") // Use G1 garbage collector
        jvmArgs("-XX:SoftRefLRUPolicyMSPerMB=50") // Soft references policy
        jvmArgs("-ea") // Enable assertions
        jvmArgs("-Djava.net.preferIPv4Stack=true") // Prefer IPv4
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005")
        jvmArgs(
            "-XX:StartFlightRecording=filename=${project.layout.buildDirectory.get().asFile}/pluginPerf.jfr,settings=profile,duration=60s,maxsize=1g,dumponexit=true",
        )
    }

    task<Exec>("e2e") {
        commandLine("./bin/e2e")
    }

    task<Exec>("format") {
        group = "format"
        commandLine("./bin/format")
    }

    task<Exec>("release") {
        group = "plugin"
        commandLine("./bin/release")
    }

    task<Exec>("installPlugin") {
        group = "plugin"
        commandLine("./bin/install")
    }

    task<Exec>("uninstallPlugin") {
        group = "plugin"
        commandLine("./bin/uninstall")
    }

    verifyPlugin {}
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")

    intellijPlatform {
        // https://www.jetbrains.com/idea/download/other.html
//        androidStudio("2024.3.2.11") // Android Studio Meerkat | 2024.3.2 Patch 11
//        androidStudio("2025.1.1.11") // Android Studio Narwhal | 2025.1.1 Patch 11
        intellijIdeaCommunity("2025.1")
//        rustRover("2025.1")
//        intellijIdeaCommunity("2023.3.8")
//        intellijIdeaCommunity("2023.1.7")
//        intellijIdeaCommunity("2023.1.2")
//        intellijIdeaUltimate("2025.1")
//        pycharmCommunity("2024.2.4")
//        pycharmCommunity("2024.3.4")
//        pycharmCommunity("2025.1.1")
//        pycharmProfessional("2025.1")
        bundledPlugins()
    }
}

configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "ch.qos.logback", module = "logback-classic")
}

kotlin {
    sourceSets {
        main {
            kotlin.srcDirs(
                "src/main/kotlin",
            )
            // Add resources directory explicitly
            resources.srcDirs("src/main/resources")
        }
    }
}
