package com.example.plugins

import com.example.model.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

// global error handler — catches exceptions thrown anywhere in the routes
// keeps the route handlers clean since they don't need their own try/catch blocks
fun Application.configureStatusPages() {
    install(StatusPages) {

        // thrown by the service when input doesn't pass validation
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Bad request")
            )
        }

        // thrown when a merchant or transaction ID doesn't exist in the DB
        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(cause.message ?: "Resource not found")
            )
        }

        // SQLState 23505 is a unique constraint violation — happens on duplicate idempotency keys
        exception<java.sql.SQLException> { call, cause ->
            if (cause.sqlState == "23505") {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("A record with the same unique key already exists: ${cause.message}")
                )
            } else {
                call.application.log.error("Database error", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Database error occurred")
                )
            }
        }

        // catch-all for anything unexpected
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("An unexpected error occurred")
            )
        }
    }
}
