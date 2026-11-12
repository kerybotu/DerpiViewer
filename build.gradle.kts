// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Keep Android Studio Sync and command-line builds on the same local output
// directory. Developers can set `derpiviewer.buildRoot` in their ignored
// local.properties; without it Gradle keeps its standard per-project build dir.
val localBuildProperties = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}
localBuildProperties.getProperty("derpiviewer.buildRoot")
    ?.takeIf(String::isNotBlank)
    ?.let { configuredRoot ->
        val externalBuildRoot = file(configuredRoot)
        allprojects {
            layout.buildDirectory.set(externalBuildRoot.resolve(name))
        }
    }
