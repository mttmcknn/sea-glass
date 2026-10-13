import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.multiplatform.library) apply false
    `maven-publish`
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
            implementation(libs.compose.material3)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(skikoMain)
        iosMain.get().dependsOn(skikoMain)
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Sea Glass")
            description.set("Tumbled sea-glass materials and components for Compose Multiplatform.")
            url.set("https://github.com/mttmcknn/sea-glass")
        }
    }
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
