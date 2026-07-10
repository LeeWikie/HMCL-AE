tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    // langchain4j-core 1.16.2 pulls in jackson-bom 2.21.3, which resolves jackson-core/
    // jackson-databind to 2.21.3. That version is affected by CVE-2026-54512 and
    // CVE-2026-54513 (PolymorphicTypeValidator bypass via generic type parameters / array
    // component types, both CVSS 8.1) and CVE-2026-54515 (@JsonIgnoreProperties bypass).
    // HMCLAI deserializes JSON returned by user-configured third-party MCP servers, i.e.
    // untrusted input, so pin both jackson modules to 2.21.5, the first 2.21.x release
    // that fixes all three (jackson-annotations is unaffected and intentionally left at
    // its transitive 2.21, matching upstream jackson-bom:2.21.5 which also keeps it at 2.21).
    constraints {
        implementation("com.fasterxml.jackson.core:jackson-databind:2.21.5") {
            because("CVE-2026-54512 / CVE-2026-54513 / CVE-2026-54515 fixed in 2.21.5; transitively pulled in at 2.21.3 via langchain4j-core's jackson-bom")
        }
        implementation("com.fasterxml.jackson.core:jackson-core:2.21.5") {
            because("Keep jackson-core in lockstep with the patched jackson-databind version")
        }
    }

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
