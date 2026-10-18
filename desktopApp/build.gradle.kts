plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":sample"))
    implementation(project(":sea-glass"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "dev.mttmcknn.seaglass.desktop.MainKt"
    }
}

tasks.register<JavaExec>("captureScreenshots") {
    group = "verification"
    description = "Capture the real Compose playground and configuration galleries with Skia."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mttmcknn.seaglass.desktop.CaptureScreenshotsKt"
    systemProperty("java.awt.headless", "true")
    args(rootProject.layout.projectDirectory.dir("docs/screenshots").asFile.absolutePath)
    providers.gradleProperty("capture").orNull?.let { args(it) }
}

tasks.register<JavaExec>("verifyPlayground") {
    group = "verification"
    description = "Check independent panes, live material changes, and resizing in the real Compose playground."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mttmcknn.seaglass.desktop.VerifyPlaygroundKt"
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("verifyLongList") {
    group = "verification"
    description = "Verify lazy list scrolling, revisit state and accessible actions; export exact-image checkpoints."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mttmcknn.seaglass.desktop.VerifyLongListKt"
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("longListOutput").getOrElse(
        layout.buildDirectory.dir("long-list-verification").get().asFile.absolutePath,
    ))
    providers.gradleProperty("longListReferences").orNull?.let { args(it) }
}

tasks.register<JavaExec>("benchmarkPlayground") {
    group = "verification"
    description = "Measure desktop Compose slider bursts and cold examples until their exact final material pixels appear."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mttmcknn.seaglass.desktop.BenchmarkPlaygroundKt"
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("benchmarkRounds").getOrElse("3"))
    args(providers.gradleProperty("benchmarkMode").getOrElse("all"))
}

tasks.register<JavaExec>("benchmarkFirstPaint") {
    group = "verification"
    description = "Measure the first visible material frame for cold insertion and repeat visits in separate reference/measurement processes."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "dev.mttmcknn.seaglass.desktop.BenchmarkFirstPaintKt"
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("firstPaintMode").getOrElse("measure"))
    args(providers.gradleProperty("firstPaintDirectory").getOrElse(
        layout.buildDirectory.dir("first-paint-references").get().asFile.absolutePath,
    ))
    args(providers.gradleProperty("firstPaintRounds").getOrElse("3"))
}
