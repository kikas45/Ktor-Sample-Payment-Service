package com.example.plugins

import com.example.model.CreateMerchantRequest
import com.example.model.ErrorResponse
import com.example.model.InitiateTransactionRequest
import com.example.service.PaymentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// all routes are defined here — no business logic, just parsing requests and calling the service
// error handling is done globally in StatusPages so we don't need try/catch everywhere
fun Application.configurePaymentRoutes(service: PaymentService) {
    routing {

        // public — no auth needed
        get("/") {
    call.respond(
        HttpStatusCode.OK, 
        mapOf(
            "status" to "UP",
            "service" to "Ktor Sample Server Payment System API",
            "timestamp" to System.currentTimeMillis()
        )
    )
}

get("/health") {
    call.respond(
        HttpStatusCode.OK, 
        mapOf(
            "status" to "UP",
            "healthy" to true,
            "timestamp" to System.currentTimeMillis()
        )
    )
}



        route("/merchants") {

            // only admins can create merchants (Basic Auth)
            authenticate("admin-basic") {
                post {
                    val body = call.receive<CreateMerchantRequest>()
                    val result = service.createMerchant(body)
                    call.respond(HttpStatusCode.Created, result)
                }
            }

            // everything else uses the bearer token
            authenticate("merchant-token") {

                get {
                    val list = service.listMerchants()
                    call.respond(HttpStatusCode.OK, list)
                }

                get("/{id}") {
                    val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Merchant ID must be an integer")
                        )
                    val merchant = service.getMerchant(id)
                    call.respond(HttpStatusCode.OK, merchant)
                }

                // supports ?status=SUCCESS&dateFrom=2026-01-01&dateTo=2026-12-31
                get("/{id}/transactions") {
                    val merchantId = call.parameters["id"]?.toIntOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Merchant ID must be an integer")
                        )

                    val status   = call.request.queryParameters["status"]
                    val dateFrom = call.request.queryParameters["dateFrom"]
                    val dateTo   = call.request.queryParameters["dateTo"]

                    val txList = service.listTransactions(merchantId, status, dateFrom, dateTo)
                    call.respond(HttpStatusCode.OK, txList)
                }

                // groups all unsettled SUCCESS transactions into a batch
                post("/{id}/settle") {
                    val merchantId = call.parameters["id"]?.toIntOrNull()
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Merchant ID must be an integer")
                        )

                    val batch = service.settleForMerchant(merchantId)
                    call.respond(HttpStatusCode.OK, batch)
                }
            }
        }

        authenticate("merchant-token") {
            post("/transactions") {
                val body = call.receive<InitiateTransactionRequest>()
                val tx = service.initiateTransaction(body)
                call.respond(HttpStatusCode.Created, tx)
            }
        }
    }
}
