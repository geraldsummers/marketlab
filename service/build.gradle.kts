plugins {
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":theory-dsl"))
    implementation(project(":data"))
    implementation(project(":engine"))
    implementation(project(":persistence"))
    implementation(project(":analytics-duckdb"))
    implementation(project(":theories"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.serialization.json)
    implementation(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("dev.marketlab.service.MainKt")
}

