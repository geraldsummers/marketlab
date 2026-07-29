plugins {
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":data"))
    implementation(project(":engine"))
    implementation(project(":theories"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("dev.marketlab.research.MainKt")
}
