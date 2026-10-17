plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.library") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
}

// Optional local output location for Windows Java worker argument files.
// Keep source files in place while avoiding non-ASCII classpath entries.
val inkBuildRoot = providers.gradleProperty("inkBuildRoot")
subprojects {
    if (inkBuildRoot.isPresent) {
        layout.buildDirectory.set(file("${inkBuildRoot.get()}/$name"))
    }
}
