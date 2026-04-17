plugins {
    id("kite.java-library-conventions")
}

dependencies {
    implementation(project(":kite-core"))
    implementation(project(":kite-openai"))
}

tasks.register<JavaExec>("runSample") {
    group = "application"
    description = "Run a sample. Usage: ./gradlew :kite-samples:runSample -Psample=io.kite.samples.basics.HelloAgent"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set((findProperty("sample") as String?) ?: "io.kite.samples.basics.HelloAgent")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
