package com.example.service

import com.example.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

// all DB access for the transactions table — plain JDBC, no ORM
class TransactionRepository(private val dataSource: DataSource) {

    // inserts a transaction in INITIATED status and returns the new row ID
    // idempotencyKey is nullable — pass null if the caller didn't send one
    suspend fun insert(
        amount: Double,
        currency: String,
        merchantRef: String,
        internalRef: String,
        fee: Double,
        merchantId: Int,
        idempotencyKey: String?
    ): Int = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO transactions
                (amount, currency, status, merchant_ref, internal_ref, fee, merchant_id, idempotency_key)
            VALUES (?, ?, 'INITIATED', ?, ?, ?, ?, ?)
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                stmt.setDouble(1, amount)
                stmt.setString(2, currency.uppercase())
                stmt.setString(3, merchantRef)
                stmt.setString(4, internalRef)
                stmt.setDouble(5, fee)
                stmt.setInt(6, merchantId)
                // setNull needed here — setString(7, null) behaves differently across drivers
                if (idempotencyKey != null) stmt.setString(7, idempotencyKey) else stmt.setNull(7, java.sql.Types.VARCHAR)
                stmt.executeUpdate()

                val keys = stmt.generatedKeys
                if (keys.next()) keys.getInt(1)
                else throw IllegalStateException("Transaction insert did not return a generated key")
            }
        }
    }

    // used after the simulated debit — moves status from INITIATED to SUCCESS (or FAILED)
    suspend fun updateStatus(id: Int, status: String) = withContext(Dispatchers.IO) {
        val sql = "UPDATE transactions SET status = ?, updated_at = NOW() WHERE id = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, status)
                stmt.setInt(2, id)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun findById(id: Int): Transaction? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM transactions WHERE id = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.toTransaction() else null
            }
        }
    }

    // used for idempotency — if the key already exists, return the original transaction
    suspend fun findByIdempotencyKey(key: String): Transaction? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM transactions WHERE idempotency_key = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.toTransaction() else null
            }
        }
    }

    // builds the WHERE clause dynamically based on which filters were passed
    // all three (status, dateFrom, dateTo) are optional
    suspend fun findByMerchant(
        merchantId: Int,
        status: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null
    ): List<Transaction> = withContext(Dispatchers.IO) {

        val conditions = mutableListOf("merchant_id = ?")
        val params     = mutableListOf<Any>(merchantId)

        if (!status.isNullOrBlank()) {
            conditions.add("status = ?")
            params.add(status.uppercase())
        }
        if (!dateFrom.isNullOrBlank()) {
            conditions.add("created_at >= ?::date")
            params.add(dateFrom)
        }
        if (!dateTo.isNullOrBlank()) {
            // add 1 day so the dateTo is inclusive of that whole day
            conditions.add("created_at < (?::date + INTERVAL '1 day')")
            params.add(dateTo)
        }

        val sql = "SELECT * FROM transactions WHERE ${conditions.joinToString(" AND ")} ORDER BY created_at DESC"

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { i, v -> stmt.setString(i + 1, v.toString()) }
                val rs = stmt.executeQuery()
                val list = mutableListOf<Transaction>()
                while (rs.next()) list.add(rs.toTransaction())
                list
            }
        }
    }

    private fun java.sql.ResultSet.toTransaction() = Transaction(
        id                = getInt("id"),
        amount            = getDouble("amount"),
        currency          = getString("currency"),
        status            = getString("status"),
        merchantRef       = getString("merchant_ref"),
        internalRef       = getString("internal_ref"),
        fee               = getDouble("fee"),
        merchantId        = getInt("merchant_id"),
        settlementBatchId = getInt("settlement_batch_id").takeIf { !wasNull() },
        idempotencyKey    = getString("idempotency_key"),
        createdAt         = getTimestamp("created_at").toString(),
        updatedAt         = getTimestamp("updated_at").toString()
    )
}
