// Market data: replica engine on the sequenced input stream, bucketing, screening, publishing.
dependencies {
    api(project(":clob-sequencer"))
    implementation(libs.agrona)
    implementation(libs.hdrhistogram)
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
