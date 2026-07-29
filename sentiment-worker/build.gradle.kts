plugins {
    application
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime)
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.lingua)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("dev.marketlab.sentiment.MainKt")
}
