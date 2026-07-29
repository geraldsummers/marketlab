dependencies {
    api(project(":contracts"))
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
}

