package com.example.model

import kotlinx.serialization.Serializable

// ─── Request bodies ────────────────────────────────────────────────────────────

@Serializable
data class CreateMerchantRequest(
    val businessName: String,
    val email: String,
    val settlementAccount: String
)

@Serializable
data class InitiateTransactionRequest(
    val amount: Double,
    val merchantRef: String,
    val merchantId: Int,
    val currency: String = "NGN",
    /** Optional caller-supplied idempotency key (e.g. UUID). */
    val idempotencyKey: String? = null
)

// ─── Domain models ─────────────────────────────────────────────────────────────

@Serializable
data class Merchant(
    val id: Int,
    val businessName: String,
    val email: String,
    val settlementAccount: String,
    val status: String,
    val createdAt: String
)

@Serializable
data class Transaction(
    val id: Int,
    val amount: Double,
    val currency: String,
    val status: String,
    val merchantRef: String,
    val internalRef: String,
    val fee: Double,
    val merchantId: Int,
    val settlementBatchId: Int? = null,
    val idempotencyKey: String? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class SettlementBatch(
    val id: Int,
    val batchRef: String,
    val merchantId: Int,
    val totalAmount: Double,
    val feeDeducted: Double,
    val transactionCount: Int,
    val netAmount: Double,
    val createdAt: String
)

// ─── Response envelopes ────────────────────────────────────────────────────────

@Serializable
data class CreatedResponse(val id: Int, val message: String)

@Serializable
data class ErrorResponse(val error: String)
