package com.example.service

import com.example.model.SettlementBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

class SettlementRepository(private val dataSource: DataSource) {

    // groups all unsettled SUCCESS transactions for a merchant into one batch
    // the whole thing runs in a transaction — if anything fails, nothing gets saved
    suspend fun settleForMerchant(merchantId: Int, batchRef: String): SettlementBatch =
        withContext(Dispatchers.IO) {

            dataSource.connection.use { conn ->
                val previousAutoCommit = conn.autoCommit
                conn.autoCommit = false // start manual transaction

                try {
                    // lock the rows first so concurrent settlement calls don't double-process them
                    val selectSql = """
                        SELECT id, amount, fee
                        FROM   transactions
                        WHERE  merchant_id         = ?
                          AND  status              = 'SUCCESS'
                          AND  settlement_batch_id IS NULL
                        FOR UPDATE
                    """.trimIndent()

                    val txIds       = mutableListOf<Int>()
                    var totalAmount = 0.0
                    var totalFee    = 0.0

                    conn.prepareStatement(selectSql).use { stmt ->
                        stmt.setInt(1, merchantId)
                        val rs = stmt.executeQuery()
                        while (rs.next()) {
                            txIds.add(rs.getInt("id"))
                            totalAmount += rs.getDouble("amount")
                            totalFee    += rs.getDouble("fee")
                        }
                    }

                    // nothing to settle — tell the caller instead of creating an empty batch
                    if (txIds.isEmpty()) {
                        throw IllegalStateException(
                            "No unsettled successful transactions found for merchant $merchantId"
                        )
                    }

                    // create the batch record
                    val insertBatchSql = """
                        INSERT INTO settlement_batches (batch_ref, merchant_id, total_amount, fee_deducted, tx_count)
                        VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()

                    val batchId: Int
                    conn.prepareStatement(insertBatchSql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                        stmt.setString(1, batchRef)
                        stmt.setInt(2, merchantId)
                        stmt.setDouble(3, totalAmount)
                        stmt.setDouble(4, totalFee)
                        stmt.setInt(5, txIds.size)
                        stmt.executeUpdate()

                        val keys = stmt.generatedKeys
                        batchId = if (keys.next()) keys.getInt(1)
                                  else throw IllegalStateException("Settlement batch insert did not return a key")
                    }

                    // stamp each transaction so we know it's been settled
                    val updateSql = """
                        UPDATE transactions
                        SET    settlement_batch_id = ?,
                               updated_at          = NOW()
                        WHERE  id = ?
                    """.trimIndent()

                    conn.prepareStatement(updateSql).use { stmt ->
                        for (id in txIds) {
                            stmt.setInt(1, batchId)
                            stmt.setInt(2, id)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }

                    conn.commit()

                    // re-fetch the batch row to get the DB-generated timestamp
                    val fetchSql = "SELECT * FROM settlement_batches WHERE id = ?"
                    conn.prepareStatement(fetchSql).use { stmt ->
                        stmt.setInt(1, batchId)
                        val rs = stmt.executeQuery()
                        if (rs.next()) {
                            SettlementBatch(
                                id               = rs.getInt("id"),
                                batchRef         = rs.getString("batch_ref"),
                                merchantId       = rs.getInt("merchant_id"),
                                totalAmount      = rs.getDouble("total_amount"),
                                feeDeducted      = rs.getDouble("fee_deducted"),
                                transactionCount = rs.getInt("tx_count"),
                                netAmount        = rs.getDouble("total_amount") - rs.getDouble("fee_deducted"),
                                createdAt        = rs.getTimestamp("created_at").toString()
                            )
                        } else {
                            throw IllegalStateException("Could not re-fetch settlement batch after commit")
                        }
                    }

                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = previousAutoCommit
                }
            }
        }
}
