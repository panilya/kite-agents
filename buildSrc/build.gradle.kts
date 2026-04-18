plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// Expose the version catalog generated accessor (`LibrariesForLibs`) to precompiled script plugins.
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
