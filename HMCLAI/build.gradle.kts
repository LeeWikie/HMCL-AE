tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    compileOnly(libs.jetbrains.annotations)
    // compileOnly is not inherited by the test classpath; declare it for tests too so the
    // explicit annotations version wins conflict resolution over the transitive 13.0 pulled
    // in via langchain4j-mcp -> okhttp -> kotlin-stdlib (which is not fully cached).
    testCompileOnly(libs.jetbrains.annotations)
    implementation(libs.gson)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.langchain4j.anthropic)
    implementation(libs.langchain4j.http.client.jdk)
    implementation(libs.langchain4j.mcp)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
}
