package io.nisfeb.talon

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every class the desktop app carries loads on the Java its release
 * bundles. 1.8.1-rc30 shipped FileKit built for Java 21 in a Java 17
 * runtime, and picking a file failed with UnsupportedClassVersionError;
 * local builds ran Java 21, so nothing here noticed. The release's JDK
 * is read from release.yml, so moving it moves this.
 */
class RuntimeClassVersionTest {

    @Test
    fun `no class needs a newer Java than the release bundles`() {
        val workflow = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, ".github/workflows/release.yml") }
            .first { it.exists() }
        val releaseJdk = Regex("""java-version: '(\d+)'""").findAll(workflow.readText()).minOf { it.groupValues[1].toInt() }
        // Class file version 52 is Java 8, 61 is 17, 65 is 21.
        val allowed = 44 + releaseJdk
        val tooNew = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .mapNotNull { path ->
                newestClass(File(path))?.takeIf { it.second > allowed }?.let { (name, v) -> "${File(path).name}: $name is $v" }
            }
        assertEquals(emptyList(), tooNew, "the release bundles Java $releaseJdk, class version $allowed at most")
    }

    /** The newest class in [jar] among its first few hundred, and its version. */
    private fun newestClass(jar: File): Pair<String, Int>? = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            // A multi-release jar's newer copies and module-info are meant for newer Javas.
            .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/versions/") && !it.name.endsWith("module-info.class") }
            .take(500)
            .map { e ->
                val head = zip.getInputStream(e).use { it.readNBytes(8) }
                e.name to (((head[6].toInt() and 0xff) shl 8) or (head[7].toInt() and 0xff))
            }
            .maxByOrNull { it.second }
    }
}
