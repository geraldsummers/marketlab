dependencies {
    api(project(":contracts"))
    api(project(":theory-dsl"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hipparchus.core)
    implementation(libs.hipparchus.stat)
    implementation(libs.slf4j.api)
}

