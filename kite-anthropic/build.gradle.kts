plugins {
    id("kite.java-library-conventions")
}

dependencies {
    api(project(":kite-core"))
    testImplementation(libs.json.schema.validator)
}
