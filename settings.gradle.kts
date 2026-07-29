rootProject.name = "marketlab"

include(
    "contracts",
    "evidence-core",
    "sentiment-core",
    "theory-dsl",
    "data",
    "engine",
    "persistence",
    "analytics-duckdb",
    "theories",
    "service",
    "worker-kotlin",
    "runner",
    "coordinator",
    "research-cli",
    "collector",
    "social-collector",
    "sentiment-worker",
    "social-backfill",
)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
