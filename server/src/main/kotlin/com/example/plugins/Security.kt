package com.example.plugins

import io.ktor.server.application.*
import io.ktor.server.auth.*

// two auth schemes:
// - admin-basic: HTTP Basic Auth, used only for creating merchants
// - merchant-token: Bearer token, used for everything else
fun Application.configureSecurity() {

    // pull from env so we're not hardcoding credentials in source code
    val adminUser     = System.getenv("ADMIN_USER")         ?: "admin"
    val adminPassword = System.getenv("ADMIN_PASSWORD")     ?: "changeme"
    val merchantToken = System.getenv("MERCHANT_API_TOKEN") ?: "merchant-secret-token"

    authentication {

        basic("admin-basic") {
            realm = "Payment System Admin"
            validate { credentials ->
                if (credentials.name == adminUser && credentials.password == adminPassword)
                    UserIdPrincipal(credentials.name)
                else
                    null
            }
        }

        // simple static token for now — good enough for this scope
        // a real system would use JWT with expiry
        bearer("merchant-token") {
            realm = "Payment System"
            authenticate { tokenCredential ->
                if (tokenCredential.token == merchantToken)
                    UserIdPrincipal("merchant")
                else
                    null
            }
        }
    }
}
