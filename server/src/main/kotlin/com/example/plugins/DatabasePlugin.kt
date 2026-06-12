package com.example.plugins

import com.example.service.Database
import com.example.service.MerchantRepository
import com.example.service.PaymentService
import com.example.service.SettlementRepository
import com.example.service.TransactionRepository
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*

// wires everything together — DB pool, migrations, repositories, service, routes
// called once at startup by the application module list in application.yaml
fun Application.configureDatabase() {
    val dataSource = Database.createDataSource()
    Database.migrate(dataSource)

    // close the pool cleanly when the server shuts down
    monitor.subscribe(ApplicationStopped) {
        dataSource.close()
        log.info("Database connection pool closed")
    }

    val merchantRepo    = MerchantRepository(dataSource)
    val transactionRepo = TransactionRepository(dataSource)
    val settlementRepo  = SettlementRepository(dataSource)
    val paymentService  = PaymentService(merchantRepo, transactionRepo, settlementRepo)

    configurePaymentRoutes(paymentService)
}
