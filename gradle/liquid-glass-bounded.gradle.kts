import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile

/** Preserve the pinned upstream AAR except the two allocator class files.
 * The source replacement lives in app/src/main/java/com/example/liquidglass.
 * No downloaded binary or generated AAR is committed to the repository.
 */
abstract class BoundedGlassAar : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val upstream: RegularFileProperty

    @get:OutputFile
    abstract val patched: RegularFileProperty

    private fun rewrite(bytes: ByteArray, classes: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                var removed = 0
                while (true) {
                    val entry = source.nextEntry ?: break
                    val allocator = entry.name == "com/example/liquidglass/DisplacementMapGenerator.class" ||
                        entry.name == "com/example/liquidglass/DisplacementMapGenerator\$Companion.class"
                    if (classes && allocator) { removed++; continue }
                    target.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    val contents = source.readBytes()
                    target.write(if (!classes && entry.name == "classes.jar") rewrite(contents, true) else contents)
                    target.closeEntry()
                }
                if (classes) check(removed == 2) { "Upstream allocator ABI changed; review the patch before building" }
            }
        }
        return output.toByteArray()
    }

    @TaskAction
    fun patch() {
        patched.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(rewrite(upstream.get().asFile.readBytes(), false))
        }
    }
}

val originalGlass = configurations.create("originalLiquidGlass") {
    isCanBeConsumed = false
    isTransitive = false
}
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies.add(originalGlass.name, catalog.findLibrary("liquid-glass").get())
val boundedGlass = tasks.register<BoundedGlassAar>("boundedLiquidGlassAar") {
    upstream.set(layout.file(providers.provider { originalGlass.singleFile }))
    patched.set(layout.buildDirectory.file("bounded-glass/liquidglass-v2.0.11-bounded.aar"))
}
dependencies.add("implementation", files(boundedGlass.flatMap { it.patched }))
