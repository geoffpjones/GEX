// Input codec, sequencer, journal, replay, output encoding and the threaded pipeline.
dependencies {
    api(project(":clob-engine"))
    implementation(libs.agrona)
    testImplementation(project(":clob-testkit"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
