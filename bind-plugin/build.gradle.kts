plugins {
    `java-library`
}

val paperApi = "io.papermc.paper:paper-api:26.3.build.205-stable"
val foliaApi = "dev.folia:folia-api:26.3.build.3-alpha"
val folia = configurations.create("folia")
val pluginVersion = project.version.toString()

dependencies {
    implementation(project(":bind-core"))
    implementation(project(":bind-storage"))
    compileOnly(paperApi)
    testImplementation(paperApi)
    testRuntimeOnly("com.google.code.gson:gson:2.14.0")
    folia(foliaApi)
}

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
    filteringCharset = "UTF-8"
}

tasks.register<JavaCompile>("compileFoliaJava") {
    source = sourceSets.main.get().allJava
    classpath = folia + files(project(":bind-core").tasks.named("jar"), project(":bind-storage").tasks.named("jar"))
    destinationDirectory.set(layout.buildDirectory.dir("classes/folia"))
    dependsOn(":bind-core:jar", ":bind-storage:jar")
}
