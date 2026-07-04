plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

// Version sourced from gradle.properties `version.server`. Independent of the client
// versions; see COMPATIBILITY.md and __docs/BRANCHING.md.
version = providers.gradleProperty("version.server").getOrElse("0.0.0")

application {
    mainClass = "org.homeflow.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    // Auth: RS256 JWKS validation against Keycloak (brings java-jwt + jwks-rsa).
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    // JSON content negotiation over the shared :core DTOs; typed-error + rate-limit plugins.
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationJson)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverRateLimit)
    // HTTP client for the Keycloak Admin API (account deletion).
    implementation(libs.ktor.clientCore)
    implementation(libs.ktor.clientCio)
    implementation(libs.ktor.clientContentNegotiation)
    // Database layer: Exposed DSL over a HikariCP pool, kotlinx-datetime column types.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlinDatetime)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.ktor.clientMock)
    testImplementation(libs.kotlin.testJunit)
    // Integration tests run against a real Postgres (Testcontainers) with the
    // production Flyway migrations applied programmatically (see __docs/TESTING.md).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
}

// Exposed 0.56.0's `exposed-kotlin-datetime` is built against kotlinx-datetime 0.6.1 and is
// binary-incompatible with 0.7.x: `TimeZoneKt.atStartOfDayIn` changed its return type to
// `kotlin.time.Instant`, so any date-column operation throws NoSuchMethodError at runtime.
// `:core` is shared with the client (which requires 0.7.1 for `kotlin.time.Clock`), but `:core`
// and the server use only stable `LocalDate` APIs — so the SERVER safely pins kotlinx-datetime
// to 0.6.1, the version Exposed 0.56.0 expects. (The client keeps 0.7.1; the modules are
// separate runtime artifacts.)
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-datetime")) {
            useVersion("0.6.1")
        }
    }
}

// Integration tests use Testcontainers. The bundled docker-java probes the daemon with
// Docker API v1.32, but modern Docker Engine/Desktop (25+) raised the MINIMUM supported
// API version above that, so the daemon rejects the probe with HTTP 400 ("client version
// too old") and Testcontainers reports "Could not find a valid Docker environment". Pin a
// modern, widely-supported API version for the forked test JVM (docker-java reads the
// `api.version` system property). An explicit DOCKER_API_VERSION from the environment
// (e.g. CI) takes precedence and disables the override.
tasks.withType<Test>().configureEach {
    if (System.getenv("DOCKER_API_VERSION") == null) {
        systemProperty("api.version", "1.43")
    }
}

// ── Flyway migrations (manual only) ──────────────────────────────────────────
// Driven through the Flyway CLI rather than the org.flywaydb.flyway Gradle plugin:
// that plugin uses Gradle APIs removed in Gradle 9 (JavaPluginConvention) and is
// incompatible with the configuration cache. The CLI approach is robust and uses
// the version catalog directly. Connection comes from env vars as the Postgres
// superuser (see DEPLOYMENT.md §5):
//   DATABASE_URL=jdbc:postgresql://<host>:5432/period_tracker
//   DATABASE_USER / DATABASE_PASSWORD
// Migrations NEVER run automatically on startup (CLAUDE.md).
val flywayCli: Configuration by configurations.creating

dependencies {
    flywayCli(libs.flyway.commandline)
    flywayCli(libs.flyway.databasePostgresql)
    flywayCli(libs.postgresql)
}

val migrationLocation =
    "filesystem:${layout.projectDirectory.dir("src/main/resources/db/migration").asFile.path}"

fun JavaExec.configureFlyway(flywayCommand: String) {
    group = "flyway"
    classpath = flywayCli
    mainClass.set("org.flywaydb.commandline.Main")
    // Copy everything the argument provider needs into locals so the lambda below
    // captures only config-cache-serializable values, never the build script object.
    val location = migrationLocation
    val url = providers.environmentVariable("DATABASE_URL")
    val user = providers.environmentVariable("DATABASE_USER")
    val password = providers.environmentVariable("DATABASE_PASSWORD")
    argumentProviders.add(
        CommandLineArgumentProvider {
            buildList {
                add(flywayCommand)
                add("-locations=$location")
                // Never let `clean` wipe a health-data database via Gradle.
                add("-cleanDisabled=true")
                url.orNull?.let { add("-url=$it") }
                user.orNull?.let { add("-user=$it") }
                password.orNull?.let { add("-password=$it") }
            }
        },
    )
}

tasks.register<JavaExec>("flywayMigrate") {
    description = "Apply pending Flyway migrations. Reads DATABASE_URL/USER/PASSWORD env vars."
    configureFlyway("migrate")
}

tasks.register<JavaExec>("flywayInfo") {
    description = "Print the status of all Flyway migrations."
    configureFlyway("info")
}

tasks.register<JavaExec>("flywayValidate") {
    description = "Validate applied migrations against the available ones."
    configureFlyway("validate")
}
