// Node wiring, admin API, metrics, drop copy, replication (warm standby), audit tool.
plugins { application }

dependencies {
    implementation(project(":clob-fix"))
    implementation(project(":clob-marketdata"))
    implementation(libs.agrona)
    implementation(libs.hdrhistogram)
}

application {
    mainClass.set("clob.app.Main")
    applicationDefaultJvmArgs = listOf("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED")
}
