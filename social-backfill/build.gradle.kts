plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":evidence-core"))
    implementation(project(":engine"))
    implementation(project(":sentiment-core"))
    implementation(libs.hipparchus.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.marketlab.backfill.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
