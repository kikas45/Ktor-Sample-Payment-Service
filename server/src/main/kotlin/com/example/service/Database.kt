package com.example.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

object Database {

    private val log = LoggerFactory.getLogger(Database::class.java)

    // reads DB config from environment variables so we don't hardcode credentials
    // DB_URL, DB_USER, DB_PASSWORD are required — app won't start without them
    // DB_POOL_SIZE is optional, defaults to 10
    fun createDataSource(): HikariDataSource {
        val url      = requireEnv("DB_URL")
        val user     = requireEnv("DB_USER")
        val password = requireEnv("DB_PASSWORD")
        val poolSize = System.getenv("DB_POOL_SIZE")?.toIntOrNull() ?: 10

        log.info("Connecting to database: $url as $user (pool=$poolSize)")

        val config = HikariConfig().apply {
            jdbcUrl          = url
            username         = user
            this.password    = password
            maximumPoolSize  = poolSize
            minimumIdle      = 2
            connectionTimeout = 30_000
            idleTimeout      = 600_000
            maxLifetime      = 1_800_000
            driverClassName  = "org.postgresql.Driver"
            poolName         = "PaymentPool"
            isAutoCommit     = true
            // keeps connections alive behind load balancers
            connectionTestQuery = "SELECT 1"
        }

        return HikariDataSource(config)
    }

    // runs on every startup — Flyway is smart enough to skip migrations that already ran
    // validateOnMigrate is off because editing the SQL file after first run causes a
    // checksum mismatch error, which is annoying in dev
    fun migrate(dataSource: DataSource) {
        log.info("Running Flyway migrations…")
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .validateOnMigrate(false)
            .load()
            .migrate()
        log.info("Flyway: ${result.migrationsExecuted} migration(s) applied")
    }

    private fun requireEnv(name: String): String =
        System.getenv(name) ?: error("Required environment variable '$name' is not set.")
}
