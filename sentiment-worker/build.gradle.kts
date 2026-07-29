plugins {
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":sentiment-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("dev.marketlab.sentiment.MainKt")
}
