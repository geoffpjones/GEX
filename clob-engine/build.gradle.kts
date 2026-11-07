// Pure matching engine: no I/O, no clock, no unseeded randomness.
dependencies {
    api(project(":clob-refdata"))
    implementation(libs.agrona)
    testImplementation(project(":clob-testkit"))
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
