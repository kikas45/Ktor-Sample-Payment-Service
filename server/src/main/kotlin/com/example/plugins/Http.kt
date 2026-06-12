package com.example.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*

fun Application.configureHttp() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // NOTE: Lock down to specific origins in production.
        anyHost()
    }

    install(Compression) {
        gzip { priority = 1.0 }
        deflate { priority = 10.0 }
    }

    install(DefaultHeaders) {
        header("X-Engine", "Ktor")
        header("X-Service", "PaymentSystem")
    }

    routing {
        // Serves the Swagger UI at /openapi
        swaggerUI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }
}
