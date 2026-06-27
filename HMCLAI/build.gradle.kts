tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    implementation(libs.gson)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j.http.client.jdk)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
}
