package com.example

import com.example.model.*
import com.example.service.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaymentServiceTest {

    private lateinit var merchantRepo:    MerchantRepository

    private lateinit var transactionRepo: TransactionRepository

    private lateinit var settlementRepo:  SettlementRepository

    private lateinit var service:         PaymentService

    private val stubMerchant = Merchant(
        id                = 1,
        businessName      = "Test Merchant Ltd",
        email             = "test@merchant.ng",
        settlementAccount = "0123456789",
        status            = "ACTIVE",
        createdAt         = "2025-01-01 00:00:00.0"
    )

    @BeforeEach
    fun setUp() {
        merchantRepo    = mockk()
        transactionRepo = mockk()
        settlementRepo  = mockk()
        service         = PaymentService(merchantRepo, transactionRepo, settlementRepo)
    }

    // ── Merchant tests ──────────────────────────────────────────────────────────

    @Test
    fun `createMerchant returns CreatedResponse with new ID`() = runBlocking {
        coEvery { merchantRepo.create(any()) } returns 5

        val result = service.createMerchant(
            CreateMerchantRequest("Test Merchant Ltd", "test@merchant.ng", "0123456789")
        )

        assertEquals(5, result.id)
        assertTrue(result.message.contains("successfully", ignoreCase = true))
        coVerify(exactly = 1) { merchantRepo.create(any()) }
    }

    @Test
    fun `createMerchant throws IllegalArgumentException for blank businessName`() = runBlocking {
        assertThrows<IllegalArgumentException> {
            service.createMerchant(
                CreateMerchantRequest("", "test@merchant.ng", "0123456789")
            )
        }
        coVerify(exactly = 0) { merchantRepo.create(any()) }

    }

    @Test
    fun `createMerchant throws IllegalArgumentException for invalid email`() = runBlocking {
        assertThrows<IllegalArgumentException> {
            service.createMerchant(
                CreateMerchantRequest("Test Merchant", "not-an-email", "0123456789")
            )
        }
        Unit
    }

    @Test
    fun `getMerchant throws NoSuchElementException when merchant not found`() = runBlocking {
        coEvery { merchantRepo.findById(99) } returns null

        assertThrows<NoSuchElementException> {
            service.getMerchant(99)
        }
        Unit
    }

    // ── Transaction tests ───────────────────────────────────────────────────────

    @Test
    fun `initiateTransaction creates transaction with correct fee for small amount`() = runBlocking {
        val expectedFee = 15.0  // 1000 * 0.015 = 15

        coEvery { merchantRepo.findById(1) }                    returns stubMerchant
        coEvery { transactionRepo.findByIdempotencyKey(any()) } returns null
        coEvery { transactionRepo.insert(any(), any(), any(), any(), any(), any(), any()) } returns 10
        coEvery { transactionRepo.updateStatus(11, "SUCCESS") } just Awaits
        coEvery { transactionRepo.findById(10) } returns stubTransaction(10, 1000.0, expectedFee)

        val tx = service.initiateTransaction(
            InitiateTransactionRequest(amount = 1000.0, merchantRef = "ORD-001", merchantId = 1)
        )

        assertEquals("SUCCESS", tx.status)
        assertEquals(expectedFee, tx.fee)
        coVerify {
            transactionRepo.insert(
                amount         = 1000.0,
                currency       = "NGN",
                merchantRef    = "ORD-001",
                internalRef    = match { it.startsWith("TXN-") },
                fee            = expectedFee,
                merchantId     = 1,
                idempotencyKey = null
            )
        }
    }

    @Test
    fun `initiateTransaction applies fee cap for large amount`() = runBlocking {
        val expectedFee = 200.0  // 50000 * 0.015 = 750, capped at 200

        coEvery { merchantRepo.findById(1) }                    returns stubMerchant
        coEvery { transactionRepo.findByIdempotencyKey(any()) } returns null
        coEvery { transactionRepo.insert(any(), any(), any(), any(), any(), any(), any()) } returns 11
        coEvery { transactionRepo.updateStatus(11, "SUCCESS") } just Awaits
        coEvery { transactionRepo.findById(11) } returns stubTransaction(11, 50000.0, expectedFee)

        val tx = service.initiateTransaction(
            InitiateTransactionRequest(amount = 50000.0, merchantRef = "ORD-002", merchantId = 1)
        )

        assertEquals(expectedFee, tx.fee)
    }

    @Test
    fun `initiateTransaction throws IllegalArgumentException for zero amount`() = runBlocking {
        assertThrows<IllegalArgumentException> {
            service.initiateTransaction(
                InitiateTransactionRequest(amount = 0.0, merchantRef = "ORD-003", merchantId = 1)
            )
        }
        Unit
    }

    @Test
    fun `initiateTransaction returns existing transaction on idempotency key replay`() = runBlocking {
        val existingTx = stubTransaction(7, 5000.0, 75.0)
        coEvery { merchantRepo.findById(1) }                         returns stubMerchant
        coEvery { transactionRepo.findByIdempotencyKey("key-abc") }  returns existingTx

        val tx = service.initiateTransaction(
            InitiateTransactionRequest(
                amount         = 5000.0,
                merchantRef    = "ORD-004",
                merchantId     = 1,
                idempotencyKey = "key-abc"
            )
        )

        assertEquals(7, tx.id)
        coVerify(exactly = 0) { transactionRepo.insert(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { transactionRepo.updateStatus(any(), any()) }
    }

    @Test
    fun `initiateTransaction throws NoSuchElementException when merchant not found`() = runBlocking {
        coEvery { merchantRepo.findById(999) } returns null

        assertThrows<NoSuchElementException> {
            service.initiateTransaction(
                InitiateTransactionRequest(amount = 100.0, merchantRef = "ORD-X", merchantId = 999)
            )
        }
        Unit
    }

    // ── Settlement tests ────────────────────────────────────────────────────────

    @Test
    fun `settleForMerchant returns batch summary`() = runBlocking {
        val expectedBatch = SettlementBatch(
            id               = 1,
            batchRef         = "BATCH-ABCDEF012345",
            merchantId       = 1,
            totalAmount      = 10000.0,
            feeDeducted      = 150.0,
            transactionCount = 2,
            netAmount        = 9850.0,
            createdAt        = "2025-06-01 12:00:00.0"
        )
        coEvery { merchantRepo.findById(1) }                   returns stubMerchant
        coEvery { settlementRepo.settleForMerchant(1, any()) } returns expectedBatch

        val result = service.settleForMerchant(1)

        assertEquals(10000.0, result.totalAmount)
        assertEquals(150.0, result.feeDeducted)
        assertEquals(9850.0, result.netAmount)
        assertEquals(2, result.transactionCount)
        assertTrue(result.batchRef.startsWith("BATCH-"))
    }

    @Test
    fun `settleForMerchant throws NoSuchElementException when merchant missing`() = runBlocking {
        coEvery { merchantRepo.findById(42) } returns null

        assertThrows<NoSuchElementException> {
            service.settleForMerchant(42)
        }
        coVerify(exactly = 0) { settlementRepo.settleForMerchant(any(), any()) }

    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun stubTransaction(id: Int, amount: Double, fee: Double) = Transaction(
        id                = id,
        amount            = amount,
        currency          = "NGN",
        status            = "SUCCESS",
        merchantRef       = "ORD-STUB",
        internalRef       = "TXN-STUB$id",
        fee               = fee,
        merchantId        = 1,
        settlementBatchId = null,
        idempotencyKey    = null,
        createdAt         = "2025-01-01 00:00:00.0",
        updatedAt         = "2025-01-01 00:00:00.0"
    )
}
