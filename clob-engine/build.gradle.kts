// Pure matching engine: no I/O, no clock, no unseeded randomness.
dependencies {
    api(project(":clob-refdata"))
    api(project(":clob-credit"))
    implementation(libs.agrona)
    testImplementation(project(":clob-testkit"))
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}
