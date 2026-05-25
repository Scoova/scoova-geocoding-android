plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
    signing
}

group = "info.scoo-va"
version = "1.1.2"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.json:json:20231013")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }

// ─── Publishing ───
// Monitor-style multi-target publish: GitHub Packages works immediately,
// JitPack also picks this layout up from the repo tag — no extra config.

java {
    withSourcesJar()
    withJavadocJar()
}

    publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])

            groupId    = "info.scoo-va"
            artifactId = "scoova-geocoding-android"
            version    = project.version.toString()

            pom {
                name.set("Scoova Geocoding SDK (Android / JVM)")
                description.set("Pelias-compatible geocoding client (search, autocomplete, reverse, place, structured, batch).")
                url.set("https://github.com/Scoova/scoova-geocoding-android")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("scoova")
                        name.set("Scoova")
                        email.set("info@scoo-va.info")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Scoova/scoova-geocoding-android.git")
                    developerConnection.set("scm:git:ssh://github.com:Scoova/scoova-geocoding-android.git")
                    url.set("https://github.com/Scoova/scoova-geocoding-android")
                }
            }
        }
    }

    repositories {
        // GitHub Packages — works immediately in Actions via GITHUB_TOKEN.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Scoova/scoova-geocoding-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as? String ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.key") as? String ?: ""
            }
        }

        // Local staging dir. `publishReleasePublicationToLocalStagingRepository`
        // writes the signed Maven layout here; the publish-to-central-portal.sh
        // script zips it and uploads to Sonatype Central Portal.
        maven {
            name = "LocalStaging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

// In-memory PGP signing — required by Maven Central. SIGNING_KEY is the
// ASCII-armored secret key; SIGNING_PASSWORD is optional (current Scoova
// release key is passphrase-less). When absent (local builds, GitHub
// Packages), signing is skipped.
signing {
    val signingKey: String? = System.getenv("SIGNING_KEY")
    val signingPassword: String = System.getenv("SIGNING_PASSWORD") ?: ""
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["release"])
    }
}