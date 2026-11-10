import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.7.1") }
}

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

    private fun skipLibraryRendererForSafeMaterial(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        var methodPatched = false
        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    ?: return null
                if (name != "drawGlassEffect" || descriptor != "(Landroid/graphics/Canvas;)V") return delegate

                methodPatched = true
                return object : MethodVisitor(Opcodes.ASM9, delegate) {
                    override fun visitCode() {
                        super.visitCode()
                        val continueRendering = Label()
                        visitVarInsn(Opcodes.ALOAD, 0)
                        visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "com/example/liquidglass/LiquidGlassView",
                            "getMaterial",
                            "()Lcom/example/liquidglass/GlassMaterial;",
                            false
                        )
                        visitFieldInsn(
                            Opcodes.GETSTATIC,
                            "com/example/liquidglass/GlassMaterial",
                            "CLEAR",
                            "Lcom/example/liquidglass/GlassMaterial;"
                        )
                        visitJumpInsn(Opcodes.IF_ACMPNE, continueRendering)
                        visitVarInsn(Opcodes.ALOAD, 0)
                        visitMethodInsn(
                            Opcodes.INVOKEVIRTUAL,
                            "com/example/liquidglass/LiquidGlassView",
                            "getAccessibilityMode",
                            "()Lcom/example/liquidglass/GlassAccessibilityMode;",
                            false
                        )
                        visitFieldInsn(
                            Opcodes.GETSTATIC,
                            "com/example/liquidglass/GlassAccessibilityMode",
                            "FORCE_FULL",
                            "Lcom/example/liquidglass/GlassAccessibilityMode;"
                        )
                        visitJumpInsn(Opcodes.IF_ACMPNE, continueRendering)
                        visitInsn(Opcodes.RETURN)
                        visitLabel(continueRendering)
                        visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                    }
                }
            }
        }, 0)
        check(methodPatched) { "LiquidGlassView.drawGlassEffect ABI changed; review the safe-material patch" }
        return writer.toByteArray()
    }

    private fun rewrite(bytes: ByteArray, classes: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
                val removedEntries = mutableSetOf<String>()
                var rendererPatched = false
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
                    val rewrittenContents = when {
                        !classes && entry.name == "classes.jar" -> rewrite(contents, true)
                        classes && entry.name == "com/example/liquidglass/LiquidGlassView.class" -> {
                            rendererPatched = true
                            skipLibraryRendererForSafeMaterial(contents)
                        }
                        else -> contents
                    }
                    target.write(rewrittenContents)
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
                    check(rendererPatched) { "LiquidGlassView ABI changed; review the safe-material patch" }
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
