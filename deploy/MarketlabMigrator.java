package dev.marketlab.deploy;

import org.flywaydb.core.Flyway;

/**
 * Deployment-only entry point. The long-running API and coordinator never
 * receive the owner credential and always start with migrations disabled.
 */
public final class MarketlabMigrator {
    private MarketlabMigrator() {}

    public static void main(String[] arguments) {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("marketlab-migrate accepts no arguments");
        }
        String url = required("MARKETLAB_DATABASE_URL");
        String user = required("MARKETLAB_DATABASE_USER");
        String password = required("MARKETLAB_DATABASE_PASSWORD");
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
            .migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
