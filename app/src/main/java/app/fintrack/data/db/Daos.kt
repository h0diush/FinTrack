package app.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query(Sql.ACCOUNTS_WITH_BALANCE)
    fun observeWithBalances(): Flow<List<AccountBalanceRow>>

    @Query(Sql.ACCOUNTS_WITH_BALANCE)
    suspend fun getWithBalances(): List<AccountBalanceRow>

    @Query(Sql.ACCOUNT_BALANCE)
    suspend fun balanceOf(accountId: String): Long?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeById(id: String): Flow<AccountEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AccountEntity)

    @Update
    suspend fun update(entity: AccountEntity)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM accounts")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE accounts SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    @Query("SELECT * FROM categories ORDER BY kind ASC, sortOrder ASC")
    suspend fun getAllOnce(): List<CategoryEntity>

    @Query("SELECT id FROM categories WHERE parentId = :parentId")
    suspend fun childIds(parentId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryEntity)

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("DELETE FROM categories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE categories SET isArchived = :archived WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, archived: Boolean)

    @Query(
        "SELECT COALESCE(MAX(sortOrder), -1) FROM categories WHERE kind = :kind" +
            " AND ((:parentId IS NULL AND parentId IS NULL) OR parentId = :parentId)",
    )
    suspend fun maxSortOrder(kind: String, parentId: String?): Int
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransactionEntity)

    @Update
    suspend fun update(entity: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Query("UPDATE transactions SET isFavorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query(Sql.DETAILS_RECENT)
    fun observeRecent(limit: Int): Flow<List<TransactionDetailsRow>>

    @Query(Sql.DETAILS_BY_ID)
    fun observeDetails(id: String): Flow<TransactionDetailsRow?>

    @Query(Sql.DETAILS_FILTERED)
    fun observeFiltered(
        start: Long?,
        end: Long?,
        type: String?,
        accountId: String?,
        categoryId: String?,
        favoritesOnly: Boolean,
    ): Flow<List<TransactionDetailsRow>>

    @Query(Sql.PERIOD_TOTALS)
    fun observeTotals(start: Long, end: Long): Flow<List<CurrencyTotalsRow>>

    @Query(Sql.STAT_ENTRIES)
    fun observeStatEntries(start: Long, end: Long): Flow<List<StatRow>>

    @Query(Sql.BALANCES_BEFORE)
    fun observeBalancesBefore(before: Long): Flow<List<CurrencyBalanceRow>>

    @Query(Sql.EARLIEST_DATE)
    fun observeEarliestDate(): Flow<Long?>

    @Query(Sql.ACCOUNT_BALANCE_BEFORE)
    suspend fun balanceBefore(accountId: String, before: Long): Long?

    /** Сумма начисленных процентов по вкладу: операции с id «dep:<счёт>:<номер>». */
    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE id LIKE :idPrefix || '%'")
    fun observeSumByIdPrefix(idPrefix: String): Flow<Long>

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId IN (:ids)")
    suspend fun countForCategories(ids: List<String>): Int
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budget_limits ORDER BY startMonth ASC, createdAt ASC")
    fun observeAll(): Flow<List<BudgetLimitEntity>>

    @Query("SELECT * FROM budget_limits")
    suspend fun getAll(): List<BudgetLimitEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BudgetLimitEntity)

    @Update
    suspend fun update(entity: BudgetLimitEntity)

    @Query("DELETE FROM budget_limits WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DepositDao {
    @Query("SELECT * FROM deposits")
    fun observeAll(): Flow<List<DepositEntity>>

    @Query("SELECT * FROM deposits")
    suspend fun getAll(): List<DepositEntity>

    @Query("SELECT * FROM deposits WHERE accountId = :accountId")
    suspend fun get(accountId: String): DepositEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DepositEntity)

    @Query("DELETE FROM deposits WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("UPDATE deposits SET postedMonths = :posted WHERE accountId = :accountId")
    suspend fun setPosted(accountId: String, posted: Int)
}

@Dao
interface PiggyDao {
    @Query("SELECT * FROM piggy_banks")
    fun observeAll(): Flow<List<PiggyEntity>>

    @Query("SELECT * FROM piggy_banks WHERE accountId = :accountId")
    suspend fun get(accountId: String): PiggyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PiggyEntity)

    @Query(Sql.PIGGY_TOP_UPS)
    fun observeTopUps(): Flow<List<PiggyTopUpRow>>
}
