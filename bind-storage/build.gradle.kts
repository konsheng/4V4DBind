plugins {
    `java-library`
}

dependencies {
    api(project(":bind-core"))
    compileOnly("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("com.google.code.gson:gson:2.14.0")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
