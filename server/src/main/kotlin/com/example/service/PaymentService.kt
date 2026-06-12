package com.example.service

import com.example.model.*
import org.slf4j.LoggerFactory
import java.util.UUID

// sits between the routes and the repositories
// business rules live here, SQL lives in the repositories
class PaymentService(
    private val merchants:    MerchantRepository,
    private val transactions: TransactionRepository,
    private val settlements:  SettlementRepository
) {

    private val log = LoggerFactory.getLogger(PaymentService::class.java)

    companion object {
        private const val FEE_RATE = 0.015  // 1.5%
        private const val FEE_CAP  = 200.0  // max fee in NGN
    }

    // ─ Merchants
    // ─ Merchants

    suspend fun createMerchant(req: CreateMerchantRequest): CreatedResponse {
        // validate before hitting the DB
        require(req.businessName.isNotBlank())     { "businessName must not be blank" }
        require(req.email.isNotBlank())             { "email must not be blank" }
        require(req.settlementAccount.isNotBlank()) { "settlementAccount must not be blank" }
        require(req.email.contains('@'))            { "email is not a valid address" }

        val id = merchants.create(req)
        log.info("Merchant created id=$id email=${req.email}")
        return CreatedResponse(id, "Merchant created successfully")
    }

    suspend fun getMerchant(id: Int): Merchant =
        merchants.findById(id) ?: throw NoSuchElementException("Merchant $id not found")

    suspend fun listMerchants(): List<Merchant> = merchants.findAll()

    // ──Transactions
    // ──Transactions
    suspend fun initiateTransaction(req: InitiateTransactionRequest): Transaction {
        require(req.amount > 0)               { "amount must be greater than zero" }
        require(req.merchantRef.isNotBlank()) { "merchantRef must not be blank" }
        require(req.merchantId > 0)           { "merchantId must be a positive integer" }

        // make sure the merchant exists before creating a transaction for them
        merchants.findById(req.merchantId)
            ?: throw NoSuchElementException("Merchant ${req.merchantId} not found")

        // if the caller sent an idempotency key and we've seen it before, just return the original
        if (!req.idempotencyKey.isNullOrBlank()) {
            val existing = transactions.findByIdempotencyKey(req.idempotencyKey)
            if (existing != null) {
                log.info("Idempotent replay for key=${req.idempotencyKey} txId=${existing.id}")
                return existing
            }
        }

        val fee         = calculateFee(req.amount)
        val internalRef = generateInternalRef()

        // save it as INITIATED first
        val id = transactions.insert(
            amount         = req.amount,
            currency       = req.currency,
            merchantRef    = req.merchantRef,
            internalRef    = internalRef,
            fee            = fee,
            merchantId     = req.merchantId,
            idempotencyKey = req.idempotencyKey
        )
        log.info("Transaction INITIATED id=$id internalRef=$internalRef amount=${req.amount} fee=$fee")

        // simulate the debit — in production this would wait for a webhook from the processor
        transactions.updateStatus(id, "SUCCESS")
        log.info("Transaction SUCCESS id=$id")

        return transactions.findById(id)
            ?: throw IllegalStateException("Could not re-fetch transaction after insert")
    }

    suspend fun listTransactions(
        merchantId: Int,
        status:     String? = null,
        dateFrom:   String? = null,
        dateTo:     String? = null
    ): List<Transaction> {
        merchants.findById(merchantId) ?: throw NoSuchElementException("Merchant $merchantId not found")
        return transactions.findByMerchant(merchantId, status, dateFrom, dateTo)
    }

    // ── Settlement ──────────────────────────────────────────────────────────────

    suspend fun settleForMerchant(merchantId: Int): SettlementBatch {
        merchants.findById(merchantId) ?: throw NoSuchElementException("Merchant $merchantId not found")

        val batchRef = generateBatchRef()
        log.info("Starting settlement merchantId=$merchantId batchRef=$batchRef")

        val batch = settlements.settleForMerchant(merchantId, batchRef)
        log.info(
            "Settlement complete batchRef=$batchRef txCount=${batch.transactionCount} " +
            "total=${batch.totalAmount} fee=${batch.feeDeducted} net=${batch.netAmount}"
        )
        return batch
    }

    // ── Helpers
    // ── Helpers

    // 1.5% of the amount, but never more than 200 as expected.
    private fun calculateFee(amount: Double): Double {
        val raw = amount * FEE_RATE
        return if (raw > FEE_CAP) FEE_CAP else raw
    }

    // e.g. TXN-A3F9C2D1E8B04762
    private fun generateInternalRef(): String =
        "TXN-" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()

    private fun generateBatchRef(): String =
        "BATCH-" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
}
