// Throughput / latency harness for the Phase 0 spike. Not part of the production artifact.
plugins { application }

dependencies {
    implementation(project(":clob-engine"))
    implementation(project(":clob-marketdata"))
    implementation(libs.agrona)
    implementation(libs.hdrhistogram)
}

application {
    mainClass.set("clob.bench.ThroughputSpike")
    // Agrona 2.x buffers use jdk.internal.misc.Unsafe.
    applicationDefaultJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")
}
