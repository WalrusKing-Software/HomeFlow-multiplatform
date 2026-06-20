plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
}

// group + version come from the root build (allprojects), sourced from
// gradle.properties `version=`. Do not hardcode here.
application {
    mainClass = "org.homeflow.ApplicationKt"
}

dependencies {
    api(projects.core)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    // Database layer: Exposed DSL over a HikariCP pool, kotlinx-datetime column types.
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlinDatetime)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    // Integration tests run against a real Postgres (Testcontainers) with the
    // production Flyway migrations applied programmatically (see __docs/TESTING.md).
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.databasePostgresql)
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
