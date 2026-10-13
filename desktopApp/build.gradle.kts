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
