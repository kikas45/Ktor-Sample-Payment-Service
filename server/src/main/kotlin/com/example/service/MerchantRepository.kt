package com.example.service

import com.example.model.CreateMerchantRequest
import com.example.model.Merchant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class MerchantRepository(private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(MerchantRepository::class.java)

    // inserts a new merchant and gives back the generated ID
    // email has a UNIQUE constraint in the DB so duplicates will throw a SQLException
    suspend fun create(req: CreateMerchantRequest): Int = withContext(Dispatchers.IO) {
        val sql = """
            INSERT INTO merchants (business_name, email, settlement_account, status)
            VALUES (?, ?, ?, 'ACTIVE')
        """.trimIndent()

        dataSource.connection.use { conn ->
            conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { stmt ->
                stmt.setString(1, req.businessName.trim())
                stmt.setString(2, req.email.trim().lowercase()) // normalize email to lowercase
                stmt.setString(3, req.settlementAccount.trim())
                stmt.executeUpdate()

                val keys = stmt.generatedKeys
                if (keys.next()) keys.getInt(1)
                else throw IllegalStateException("Merchant insert did not return a generated key")
            }
        }
    }

    // returns null instead of throwing when merchant doesn't exist
    // callers decide what to do with the null
    suspend fun findById(id: Int): Merchant? = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM merchants WHERE id = ?"
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.toMerchant() else null
            }
        }
    }

    // newest merchants first
    suspend fun findAll(): List<Merchant> = withContext(Dispatchers.IO) {
        val sql = "SELECT * FROM merchants ORDER BY created_at DESC"
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                val list = mutableListOf<Merchant>()
                while (rs.next()) list.add(rs.toMerchant())
                list
            }
        }
    }

    // maps a result set row to the Merchant data class
    private fun java.sql.ResultSet.toMerchant() = Merchant(
        id                = getInt("id"),
        businessName      = getString("business_name"),
        email             = getString("email"),
        settlementAccount = getString("settlement_account"),
        status            = getString("status"),
        createdAt         = getTimestamp("created_at").toString()
    )
}
