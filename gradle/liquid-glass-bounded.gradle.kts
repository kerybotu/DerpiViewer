import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile

/** Preserve the pinned upstream AAR except bounded class replacements.
 * Source replacements live in app/src/main/java/com/example/liquidglass.
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
                val removedEntries = mutableSetOf<String>()
                while (true) {
                    val entry = source.nextEntry ?: break
                    // The Kotlin compiler also emits synthetic classes for the
                    // generator's `when` expressions (for example
                    // `DisplacementMapGenerator$WhenMappings`).  Leaving one
                    // of those in the upstream AAR makes D8 see duplicate
                    // classes because the bounded source replacement emits it
                    // again.  Remove the complete generated class family.
                    val generatorClass = "com/example/liquidglass/DisplacementMapGenerator"
                    val allocator = (entry.name == "$generatorClass.class" ||
                        entry.name.startsWith("${generatorClass}\$")) && entry.name.endsWith(".class")
                    val backdropClass = "com/example/liquidglass/BackdropCapture"
                    val backdropCapture = (entry.name == "$backdropClass.class" ||
                        entry.name.startsWith("${backdropClass}\$")) && entry.name.endsWith(".class")
                    if (classes && (allocator || backdropCapture)) {
                        removedEntries += entry.name
                        continue
                    }
                    target.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                    val contents = source.readBytes()
                    target.write(if (!classes && entry.name == "classes.jar") rewrite(contents, true) else contents)
                    target.closeEntry()
                }
                if (classes) {
                    check(removedEntries.containsAll(setOf(
                        "com/example/liquidglass/DisplacementMapGenerator.class",
                        "com/example/liquidglass/DisplacementMapGenerator\$Companion.class",
                        "com/example/liquidglass/BackdropCapture.class",
                        "com/example/liquidglass/BackdropCapture\$Companion.class"
                    ))) {
                        "Upstream glass ABI changed; review the patch before building"
                    }
                }
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
