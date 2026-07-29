plugins {
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":engine"))
    implementation(project(":data"))
    implementation(project(":analytics-duckdb"))
    implementation(project(":persistence"))
    implementation(project(":theories"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hipparchus.stat)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("dev.marketlab.worker.MainKt")
}
