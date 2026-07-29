plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":contracts"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime)
    implementation(libs.djl.huggingface.tokenizers)
    implementation(libs.lingua)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
