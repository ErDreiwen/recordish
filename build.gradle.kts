import org.apache.commons.lang3.SystemUtils

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.5"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

val baseGroup: String by project
val mcVersion: String by project
val modid: String by project
val modVersion: String by project

group = baseGroup
version = modVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

loom {
    launchConfigs {
        "client" {
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        mixinConfig("mixins.recordable.json")
    }
    mixin {
        defaultRefmapName.set("mixins.recordable.refmap.json")
    }
}

sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
}

val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

/*
 * Loom 0.10's RunGameTask predates Gradle 8's annotated-property model.
 * Copy its computed launch configuration into a current JavaExec so the
 * legacy client remains runnable without weakening task validation.
 */
val legacyRunClient =
    tasks.named<net.fabricmc.loom.task.RunGameTask>("runClient")
tasks.register<JavaExec>("runClientCompat") {
    group = "loom"
    description = "Runs the Minecraft 1.8.9 client on Java 8"
    dependsOn("classes", "downloadAssets")

    val legacy = legacyRunClient.get()
    classpath = legacy.classpath
    mainClass.set(legacy.main)
    args(legacy.args)
    jvmArgs(legacy.jvmArgs)
    workingDir(file("run"))
    standardInput = System.`in`
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
}

val smokeFfmpeg = providers.gradleProperty("recordableSmokeFfmpeg")
tasks.register<JavaExec>("pipelineSmokeTest") {
    group = "verification"
    description = "Runs the Record-able raw-video and audio finalization smoke test"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.recordable.RecordingPipelineSmoke")
    args(
        smokeFfmpeg.orElse("").get(),
        layout.buildDirectory.dir("pipeline-smoke").get().asFile.absolutePath
    )
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    doFirst {
        if (!smokeFfmpeg.isPresent) {
            throw GradleException(
                "Pass -PrecordableSmokeFfmpeg=<absolute path to ffmpeg>."
            )
        }
    }
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(modid)
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-recordable.txt" }
    }
    from("UPSTREAM.md") {
        into("META-INF")
        rename { "UPSTREAM-recordable.md" }
    }
    manifest.attributes(
        "Implementation-Title" to "Record-able",
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "Record-able contributors",
        "FMLCorePluginContainsFMLMod" to "true",
        "ForceLoadAsMod" to "true",
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.recordable.json"
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    filesMatching(listOf("mcmod.info", "mixins.recordable.json")) {
        expand(inputs.properties)
    }
}

val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveFileName.set("$modid-$modVersion.jar")
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}

tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
}

tasks.assemble {
    dependsOn(remapJar)
}
