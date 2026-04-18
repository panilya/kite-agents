plugins {
    id("kite.java-library-conventions")
}

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jdk8)
    api(libs.jackson.datatype.jsr310)
    testImplementation(libs.json.schema.validator)
}
