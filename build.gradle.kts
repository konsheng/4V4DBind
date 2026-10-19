plugins {
    java
}

allprojects {
    group = "com.vvvvdddd"
    version = "1.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content {
                includeGroup("io.papermc.paper")
                includeGroup("dev.folia")
                includeGroup("net.minecraft")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-Dfile.encoding=UTF-8")
        testLogging { events("failed", "skipped") }
    }
}

tasks.jar {
    archiveFileName.set("4V4DBind-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    subprojects.forEach { module ->
        dependsOn(module.tasks.named("classes"))
        from(module.extensions.getByType<SourceSetContainer>().named("main").map { it.output })
    }
}

tasks.check {
    dependsOn(subprojects.map { "${it.path}:check" })
}

tasks.register("verifyFoliaApi") {
    group = "verification"
    description = "Checks the plugin sources against the pinned Folia API"
    dependsOn(":bind-plugin:compileFoliaJava")
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
