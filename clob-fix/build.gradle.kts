// FIX 4.4 order-entry gateway (acceptor): session layer, order translation, execution reports.
dependencies {
    api(project(":clob-sequencer"))
    implementation(libs.agrona)
    testImplementation(libs.quickfixj.core)
    testImplementation(libs.quickfixj.fix44)
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
}
