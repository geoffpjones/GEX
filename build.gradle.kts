subprojects {
    apply(plugin = "java-library")

    group = "clob"
    version = "0.1.0-SNAPSHOT"

    repositories { mavenCentral() }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-this-escape", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Agrona 2.x buffers and ring buffers use jdk.internal.misc.Unsafe.
        jvmArgs("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
