rootProject.name = "kite"

include(":kite-core")
include(":kite-openai")
include(":kite-anthropic")
include(":kite-samples")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
