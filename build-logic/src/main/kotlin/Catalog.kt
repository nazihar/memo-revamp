import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

/** Typed access to `gradle/libs.versions.toml` from convention plugins. */
val Project.libs: LibrariesForLibs
    get() = the<LibrariesForLibs>()
