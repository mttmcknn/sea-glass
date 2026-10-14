import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.maven.publish)
}

if (providers.gradleProperty("seaGlass.android").orNull != "false") {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(17)
    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        namespace = "dev.mttmcknn.seaglass"
        compileSdk = 36
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        // Compose's device-test asset task needs AGP's resource source directories.
        androidResources.enable = true
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "HOST"
        }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.coroutines.get()}")
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        findByName("androidDeviceTest")?.dependencies {
            implementation(kotlin("test-junit"))
            implementation("androidx.test:runner:1.7.0")
        }
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(skikoMain)
        iosMain.get().dependsOn(skikoMain)
    }
}

mavenPublishing {
    coordinates(artifactId = "sea-glass")
    publishToMavenCentral(automaticRelease = false)
    signAllPublications()

    pom {
        name.set("Sea Glass")
        description.set("Tumbled sea-glass materials and components for Compose Multiplatform.")
        url.set("https://github.com/mttmcknn/sea-glass")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("mttmcknn")
                name.set("mttmcknn")
                url.set("https://github.com/mttmcknn")
            }
        }
        scm {
            url.set("https://github.com/mttmcknn/sea-glass")
            connection.set("scm:git:https://github.com/mttmcknn/sea-glass.git")
            developerConnection.set("scm:git:ssh://git@github.com/mttmcknn/sea-glass.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "localVerification"
            url = uri(layout.buildDirectory.dir("verification-repository"))
        }
    }
}

val validatePublicationTargets by tasks.registering {
    group = "publishing"
    description = "Require the complete Android, JVM, and iOS publication set."
    inputs.property("androidEnabled", providers.gradleProperty("seaGlass.android").orElse("true"))
    inputs.property("hostOs", providers.systemProperty("os.name"))
    doLast {
        check(inputs.properties["androidEnabled"] != "false") {
            "Publication requires Android. Remove -PseaGlass.android=false."
        }
        check(inputs.properties["hostOs"].toString().startsWith("Mac", ignoreCase = true)) {
            "Publish the complete target set from macOS with Xcode installed."
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(validatePublicationTargets)
}

val benchmarkCompilation = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("test")
tasks.register<JavaExec>("benchmarkRenderer") {
    group = "verification"
    description = "Measure renderer and native pixel upload costs with deterministic material workloads."
    dependsOn(benchmarkCompilation.compileTaskProvider)
    classpath = files(benchmarkCompilation.output.allOutputs, benchmarkCompilation.runtimeDependencyFiles)
    mainClass.set("dev.mttmcknn.seaglass.internal.RendererBenchmarkKt")
    systemProperty("java.awt.headless", "true")
    minHeapSize = "256m"
    maxHeapSize = "1g"
    args(providers.gradleProperty("benchmarkOutput").getOrElse(layout.buildDirectory.file("reports/sea-glass/renderer-benchmark.json").get().asFile.absolutePath))
    providers.gradleProperty("benchmarkSamples").orNull?.let { args("--samples=$it") }
    providers.gradleProperty("benchmarkWarmups").orNull?.let { args("--warmups=$it") }
    if (providers.gradleProperty("benchmarkFastGround").orNull == "true") args("--fast-ground")
    providers.gradleProperty("benchmarkProfile").orNull?.let {
        jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile,dumponexit=true")
    }
}
