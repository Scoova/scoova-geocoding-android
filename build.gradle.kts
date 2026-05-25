plugins {
    kotlin("jvm") version "2.2.0"
    `maven-publish`
    signing
}

group = "info.scoo-va"
version = "1.1.1"

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
// Maven Central is staged for when the Sonatype account is provisioned.
// JitPack also picks this layout up from the repo tag — no extra config.

java {
    withSourcesJar()
    withJavadocJar()
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["java"])

                groupId = "info.scoo-va"
                artifactId = "scoova-geocoding"
                version = project.version.toString()

                pom {
                    name.set("Scoova Geocoding Android SDK")
                    description.set(
                        "Pelias-compatible geocoding client for api.scoo-va.info/api/v1/geocoding " +
                        "— search, autocomplete, reverse, place, structured, batch."
                    )
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
            // GitHub Packages — credentials come from env (GH Actions) or
            // ~/.gradle/gradle.properties (`gpr.user` + `gpr.key`).
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Scoova/scoova-geocoding-android")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                        ?: project.findProperty("gpr.user") as? String ?: ""
                    password = System.getenv("GITHUB_TOKEN")
                        ?: project.findProperty("gpr.key") as? String ?: ""
                }
            }

            // Maven Central staging — used once the Sonatype account is live.
            maven {
                name = "MavenCentral"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = System.getenv("OSSRH_USERNAME")
                        ?: project.findProperty("ossrh.username") as? String ?: ""
                    password = System.getenv("OSSRH_PASSWORD")
                        ?: project.findProperty("ossrh.password") as? String ?: ""
                }
            }
        }
    }

    // GPG signing — only enforced when publishing to Maven Central.
    signing {
        isRequired = gradle.taskGraph.hasTask("publishReleasePublicationToMavenCentralRepository")
        sign(publishing.publications["release"])
    }
}
