import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import crow.evilo.buildlogic.eviloProperty
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("evilo.multiplatform")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.wasmline)
    alias(libs.plugins.mavenPublish)
}

group = "io.github.crowforkotlin.evilo"
// The SDK ships as part of an app release and shares its version; project.properties owns it.
version = eviloProperty("EVILO_VERSION_NAME")

configure<MavenPublishBaseExtension> {
    configure(
        platform = KotlinMultiplatform(
            // Central accepts placeholder javadoc JARs; generating Dokka HTML per target
            // duplicated roughly 1 MiB across eight artifacts. The module's KDoc lives in
            // the repository, so publishing empty placeholders keeps the quota for real code.
            javadocJar = JavadocJar.Empty(),
            androidVariantsToPublish = emptyList(),
        ),
    )
    publishToMavenCentral(automaticRelease = true)
    // Local `publishToMavenLocal` must not require a GPG key; CI provides signing inputs.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("evilo library")
        description.set(
            "Kotlin Multiplatform media models and the plugin/host protocol used by the evilo " +
                "application and its Wasmline plugins.",
        )
        url.set("https://github.com/crowforkotlin/evilo/")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("crowforkotlin")
                name.set("wuya")
            }
        }
        scm {
            url.set("https://github.com/crowforkotlin/evilo/")
            connection.set("scm:git:https://github.com/crowforkotlin/evilo.git")
            developerConnection.set("scm:git:ssh://git@github.com/crowforkotlin/evilo.git")
        }
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    sourceSets.commonMain.dependencies {
        api(libs.wasmline)
        api(libs.kotlinx.serialization.core)
        api(libs.kotlinx.serialization.json)
    }
}
