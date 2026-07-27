import groovy.json.JsonSlurper
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.jar.JarFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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
val artifactTarget: String by project

val versionFile = layout.projectDirectory.file("version.txt")
val baseVersion =
    providers.gradleProperty("modVersion")
        .orElse(
            providers.fileContents(versionFile)
                .asText
                .map { it.trim() }
        )
        .get()
val semanticVersionPattern =
    Regex(
        """(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)"""
            + """(?:-(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-]"""
            + """[0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|"""
            + """[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?"""
            + """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
    )
if (!semanticVersionPattern.matches(baseVersion)) {
    throw GradleException(
        "modVersion/version.txt must contain SemVer such as 1.2.3 or "
            + "1.2.3-nightly.42.gabc123; received '$baseVersion'."
    )
}
if (!Regex("""[A-Za-z0-9][A-Za-z0-9._-]*""").matches(artifactTarget)) {
    throw GradleException(
        "artifactTarget contains unsupported characters: '$artifactTarget'."
    )
}
val runtimeVersion = "$baseVersion-$artifactTarget"

fun normalizeReleaseArchive(archive: File) {
    val archivePath = archive.toPath()
    val normalizedPath =
        archivePath.resolveSibling("${archive.name}.normalized")
    Files.deleteIfExists(normalizedPath)

    try {
        ZipFile(archive).use { source ->
            ZipOutputStream(
                BufferedOutputStream(Files.newOutputStream(normalizedPath))
            ).use { output ->
                output.setLevel(Deflater.DEFAULT_COMPRESSION)
                source.comment
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { output.setComment(it) }

                val fixedTimestamp =
                    LocalDateTime.of(1980, 1, 1, 0, 0)
                val orderedEntries =
                    source.entries()
                        .asSequence()
                        .toList()
                        .sortedWith(
                            compareBy<ZipEntry> {
                                if (it.name == "META-INF/MANIFEST.MF") {
                                    0
                                } else {
                                    1
                                }
                            }.thenBy { it.name }
                        )
                orderedEntries.forEach { original ->
                    if (original.method != ZipEntry.STORED
                        && original.method != ZipEntry.DEFLATED
                    ) {
                        throw GradleException(
                            "Unsupported ZIP compression method "
                                + "${original.method} for ${original.name}."
                        )
                    }

                    val data =
                        source.getInputStream(original).use { it.readBytes() }
                    val normalized = ZipEntry(original.name)
                    normalized.method = original.method
                    normalized.comment = original.comment
                    normalized.timeLocal = fixedTimestamp

                    if (normalized.method == ZipEntry.STORED) {
                        val crc = CRC32()
                        crc.update(data)
                        normalized.size = data.size.toLong()
                        normalized.compressedSize = data.size.toLong()
                        normalized.crc = crc.value
                    }

                    output.putNextEntry(normalized)
                    if (data.isNotEmpty()) {
                        output.write(data)
                    }
                    output.closeEntry()
                }
            }
        }

        try {
            Files.move(
                normalizedPath,
                archivePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                normalizedPath,
                archivePath,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    } finally {
        Files.deleteIfExists(normalizedPath)
    }
}

group = baseGroup
version = runtimeVersion

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
        mixinConfig("mixins.recordish.json")
    }
    mixin {
        defaultRefmapName.set("mixins.recordish.refmap.json")
    }
}

val generatedBuildInfoDirectory =
    layout.buildDirectory.dir("generated/sources/buildInfo/java/main")
val generatedBuildInfoFile =
    generatedBuildInfoDirectory.map {
        it.file("dev/recordish/BuildInfo.java")
    }
val generateBuildInfo by tasks.registering {
    description = "Generates compile-time version metadata."
    inputs.property("version", runtimeVersion)
    outputs.file(generatedBuildInfoFile)
    doLast {
        val output = generatedBuildInfoFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            package dev.recordish;

            public final class BuildInfo {
                public static final String VERSION = "$runtimeVersion";

                private BuildInfo() {
                }
            }
            """.trimIndent() + "\n",
            Charsets.UTF_8
        )
    }
}

sourceSets.main {
    java.srcDir(generatedBuildInfoDirectory)
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateBuildInfo)
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

val smokeFfmpeg =
    providers.gradleProperty("recordishSmokeFfmpeg")
        .orElse(providers.gradleProperty("recordableSmokeFfmpeg"))
tasks.register<JavaExec>("pipelineSmokeTest") {
    group = "verification"
    description = "Runs the Recordish raw-video and audio finalization smoke test"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.recordish.RecordingPipelineSmoke")
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
                "Pass -PrecordishSmokeFfmpeg=<absolute path to ffmpeg>."
            )
        }
    }
}

val ffmpegInstallerSmokeRoot =
    layout.buildDirectory.dir("ffmpeg-installer-smoke")
tasks.register<JavaExec>("ffmpegInstallerSmokeTest") {
    group = "verification"
    description =
        "Downloads, verifies, stages, publishes, and probes managed FFmpeg"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.recordish.FfmpegInstallerSmoke")
    args(
        ffmpegInstallerSmokeRoot
            .map { it.dir("bin") }
            .get()
            .asFile
            .absolutePath
    )
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    doFirst {
        project.delete(ffmpegInstallerSmokeRoot)
    }
}

val identityMigrationSmokeRoot =
    layout.buildDirectory.dir("identity-migration-smoke")
tasks.register<JavaExec>("identityMigrationSmokeTest") {
    group = "verification"
    description = "Verifies Record-able settings and data-path compatibility"
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.recordish.IdentityMigrationSmoke")
    args(identityMigrationSmokeRoot.get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    doFirst {
        project.delete(identityMigrationSmokeRoot)
    }
}

tasks.check {
    dependsOn("identityMigrationSmokeTest")
}

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(modid)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-recordish.txt" }
    }
    from("UPSTREAM.md") {
        into("META-INF")
        rename { "UPSTREAM-recordish.md" }
    }
    manifest.attributes(
        "Implementation-Title" to "Recordish",
        "Implementation-Version" to project.version,
        "Implementation-Vendor" to "Recordish contributors",
        "FMLCorePluginContainsFMLMod" to "true",
        "ForceLoadAsMod" to "true",
        "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
        "MixinConfigs" to "mixins.recordish.json"
    )
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    filesMatching(listOf("mcmod.info", "mixins.recordish.json")) {
        expand(inputs.properties)
    }
}

val remapJar =
    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        archiveFileName.set("$modid-$runtimeVersion.jar")
        archiveClassifier.set("")
        from(tasks.shadowJar)
        input.set(tasks.shadowJar.get().archiveFile)
        doLast {
            normalizeReleaseArchive(archiveFile.get().asFile)
        }
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

val verifyReleaseJar by tasks.registering {
    group = "verification"
    description =
        "Verifies release metadata and Java 8 bytecode compatibility."
    dependsOn(remapJar)
    inputs.file(remapJar.flatMap { it.archiveFile })
    inputs.property("expectedVersion", runtimeVersion)

    doLast {
        val releaseJar = remapJar.get().archiveFile.get().asFile
        if (!releaseJar.isFile || releaseJar.length() <= 0L) {
            throw GradleException(
                "Release JAR is missing or empty: $releaseJar"
            )
        }

        JarFile(releaseJar).use { jar ->
            val manifestVersion =
                jar.manifest
                    ?.mainAttributes
                    ?.getValue("Implementation-Version")
            if (manifestVersion != runtimeVersion) {
                throw GradleException(
                    "Manifest version '$manifestVersion' does not match "
                        + "'$runtimeVersion'."
                )
            }

            val modInfoEntry =
                jar.getJarEntry("mcmod.info")
                    ?: throw GradleException(
                        "Release JAR does not contain mcmod.info."
                    )
            val modInfoText =
                jar.getInputStream(modInfoEntry)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            val modInfo =
                JsonSlurper().parseText(modInfoText) as? List<*>
                    ?: throw GradleException(
                        "mcmod.info must contain a JSON array."
                    )
            val modMetadata =
                modInfo.firstOrNull() as? Map<*, *>
                    ?: throw GradleException(
                        "mcmod.info does not contain mod metadata."
                    )
            val metadataVersion = modMetadata["version"]?.toString()
            if (metadataVersion != runtimeVersion) {
                throw GradleException(
                    "mcmod.info version '$metadataVersion' does not match "
                        + "'$runtimeVersion'."
                )
            }

            var classCount = 0
            val incompatibleClasses = mutableListOf<String>()
            val invalidClasses = mutableListOf<String>()
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class")) {
                    continue
                }
                classCount++
                val header = ByteArray(8)
                val bytesRead =
                    jar.getInputStream(entry).use { input ->
                        var offset = 0
                        while (offset < header.size) {
                            val count =
                                input.read(
                                    header,
                                    offset,
                                    header.size - offset
                                )
                            if (count < 0) break
                            offset += count
                        }
                        offset
                    }
                val validMagic =
                    bytesRead == header.size
                        && (header[0].toInt() and 0xFF) == 0xCA
                        && (header[1].toInt() and 0xFF) == 0xFE
                        && (header[2].toInt() and 0xFF) == 0xBA
                        && (header[3].toInt() and 0xFF) == 0xBE
                if (!validMagic) {
                    invalidClasses.add(entry.name)
                    continue
                }
                val majorVersion =
                    ((header[6].toInt() and 0xFF) shl 8) or
                        (header[7].toInt() and 0xFF)
                if (majorVersion > 52) {
                    incompatibleClasses.add(
                        "${entry.name} (major $majorVersion)"
                    )
                }
            }
            if (classCount == 0) {
                throw GradleException(
                    "Release JAR does not contain any class files."
                )
            }
            if (invalidClasses.isNotEmpty()) {
                throw GradleException(
                    "Invalid class headers: "
                        + invalidClasses.joinToString(", ")
                )
            }
            if (incompatibleClasses.isNotEmpty()) {
                throw GradleException(
                    "Release JAR contains bytecode newer than Java 8: "
                        + incompatibleClasses.joinToString(", ")
                )
            }
            logger.lifecycle(
                "Verified ${releaseJar.name}: version $runtimeVersion, "
                    + "$classCount Java 8-compatible classes."
            )
        }
    }
}

tasks.check {
    dependsOn(verifyReleaseJar)
}

tasks.build {
    dependsOn(verifyReleaseJar)
}
