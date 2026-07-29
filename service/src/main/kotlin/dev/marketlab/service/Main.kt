package dev.marketlab.service

import dev.marketlab.persistence.Database
import dev.marketlab.persistence.DatabaseConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val serviceConfig = ServiceConfig.fromEnvironment()
    val database = Database.open(DatabaseConfig.fromEnvironment())
    Runtime.getRuntime().addShutdownHook(Thread(database::close, "marketlab-database-shutdown"))
    registerAcademicTheories(database.dataSource)
    embeddedServer(
        factory = Netty,
        host = serviceConfig.host,
        port = serviceConfig.port,
    ) {
        configureHttpService(
            serviceConfig,
            DatabaseServiceBackend(database.dataSource),
            FileInformationStatusProvider(
                rawRoot = serviceConfig.socialRawRoot,
                featureRoot = serviceConfig.socialFeatureRoot,
                universeRoot = serviceConfig.socialUniverseRoot,
                modelLock = serviceConfig.sentimentModelLock,
            ),
        )
    }.start(wait = true)
}
