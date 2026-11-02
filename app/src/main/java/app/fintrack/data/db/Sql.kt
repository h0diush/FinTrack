package app.fintrack.data.db

/**
 * SQL-запросы в одном месте. Правила балансов совпадают с
 * domain.logic.BalanceCalculator; tools/verify_sql.py и LedgerDaoTest
 * проверяют, что результаты идентичны.
 */
object Sql {

    /**
     * Пополнения копилок: число входящих переводов и сумма последнего.
     * Последний — по дате, при равенстве — по времени создания.
     */
    const val PIGGY_TOP_UPS = "SELECT t.toAccountId AS accountId, COUNT(*) AS topUps," +
        " (SELECT t3.toAmountMinor FROM transactions t3 WHERE t3.type = 'TRANSFER' AND t3.toAccountId = t.toAccountId" +
        " ORDER BY t3.dateTime DESC, t3.createdAt DESC LIMIT 1) AS lastAmountMinor" +
        " FROM transactions t JOIN accounts a ON a.id = t.toAccountId" +
        " WHERE t.type = 'TRANSFER' AND a.type = 'PIGGY' GROUP BY t.toAccountId"

    /** Баланс счёта `a`: начальный остаток + все движения по нему. */
    const val BALANCE_EXPR = "(a.initialBalanceMinor" +
        " + COALESCE((SELECT SUM(CASE t.type" +
        " WHEN 'INCOME' THEN t.amountMinor" +
        " WHEN 'EXPENSE' THEN -t.amountMinor" +
        " WHEN 'TRANSFER' THEN -(t.amountMinor + t.feeMinor)" +
        " WHEN 'ADJUSTMENT' THEN t.amountMinor" +
        " ELSE 0 END) FROM transactions t WHERE t.accountId = a.id), 0)" +
        " + COALESCE((SELECT SUM(t2.toAmountMinor) FROM transactions t2" +
        " WHERE t2.type = 'TRANSFER' AND t2.toAccountId = a.id), 0))"

    /** Баланс счёта `a` на момент :before (операции строго раньше). */
    const val BALANCE_BEFORE_EXPR = "(a.initialBalanceMinor" +
        " + COALESCE((SELECT SUM(CASE t.type" +
        " WHEN 'INCOME' THEN t.amountMinor" +
        " WHEN 'EXPENSE' THEN -t.amountMinor" +
        " WHEN 'TRANSFER' THEN -(t.amountMinor + t.feeMinor)" +
        " WHEN 'ADJUSTMENT' THEN t.amountMinor" +
        " ELSE 0 END) FROM transactions t WHERE t.accountId = a.id AND t.dateTime < :before), 0)" +
        " + COALESCE((SELECT SUM(t2.toAmountMinor) FROM transactions t2" +
        " WHERE t2.type = 'TRANSFER' AND t2.toAccountId = a.id AND t2.dateTime < :before), 0))"

    /** Суммарный баланс по валютам на момент :before — начальная точка графика баланса. */
    const val BALANCES_BEFORE = "SELECT a.currencyCode AS currencyCode, SUM(" + BALANCE_BEFORE_EXPR + ") AS balanceMinor" +
        " FROM accounts a GROUP BY a.currencyCode"

    /** Операции периода для статистики и бюджетов; агрегирование — в Kotlin с учётом часового пояса. */
    const val STAT_ENTRIES = "SELECT t.type AS type, t.accountId AS accountId, t.toAccountId AS toAccountId," +
        " t.amountMinor AS amountMinor, t.toAmountMinor AS toAmountMinor, t.feeMinor AS feeMinor," +
        " t.dateTime AS dateTime, a.currencyCode AS currency, ta.currencyCode AS toCurrency," +
        " t.categoryId AS categoryId, COALESCE(c.parentId, c.id) AS rootCategoryId" +
        " FROM transactions t" +
        " INNER JOIN accounts a ON a.id = t.accountId" +
        " LEFT JOIN accounts ta ON ta.id = t.toAccountId" +
        " LEFT JOIN categories c ON c.id = t.categoryId" +
        " WHERE t.dateTime >= :start AND t.dateTime < :end"

    const val EARLIEST_DATE = "SELECT MIN(dateTime) FROM transactions"

    /** Баланс одного счёта на момент :before — база для начисления процентов по вкладу. */
    const val ACCOUNT_BALANCE_BEFORE = "SELECT " + BALANCE_BEFORE_EXPR + " FROM accounts a WHERE a.id = :accountId"

    const val ACCOUNTS_WITH_BALANCE = "SELECT a.*, " + BALANCE_EXPR + " AS balanceMinor" +
        " FROM accounts a ORDER BY a.isArchived ASC, a.sortOrder ASC, a.createdAt ASC"

    const val ACCOUNT_BALANCE = "SELECT " + BALANCE_EXPR + " FROM accounts a WHERE a.id = :accountId"

    /** Доходы и расходы по валютам счетов. Расход включает комиссии переводов. */
    const val PERIOD_TOTALS = "SELECT a.currencyCode AS currencyCode," +
        " COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amountMinor ELSE 0 END), 0) AS incomeMinor," +
        " COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amountMinor" +
        " WHEN t.type = 'TRANSFER' THEN t.feeMinor ELSE 0 END), 0) AS expenseMinor" +
        " FROM transactions t INNER JOIN accounts a ON a.id = t.accountId" +
        " WHERE t.dateTime >= :start AND t.dateTime < :end" +
        " GROUP BY a.currencyCode"

    private const val DETAILS_SELECT = "SELECT t.*," +
        " a.name AS accountName, a.currencyCode AS accountCurrency," +
        " ta.name AS toAccountName, ta.currencyCode AS toAccountCurrency," +
        " c.name AS categoryName, c.iconKey AS categoryIcon, c.color AS categoryColor," +
        " p.name AS parentCategoryName" +
        " FROM transactions t" +
        " INNER JOIN accounts a ON a.id = t.accountId" +
        " LEFT JOIN accounts ta ON ta.id = t.toAccountId" +
        " LEFT JOIN categories c ON c.id = t.categoryId" +
        " LEFT JOIN categories p ON p.id = c.parentId"

    private const val DETAILS_ORDER = " ORDER BY t.dateTime DESC, t.createdAt DESC"

    const val DETAILS_RECENT = DETAILS_SELECT + DETAILS_ORDER + " LIMIT :limit"

    const val DETAILS_BY_ID = DETAILS_SELECT + " WHERE t.id = :id"

    /** Фильтры по периоду, типу, счёту и категории (с подкатегориями). Поиск и сумма — в Kotlin. */
    const val DETAILS_FILTERED = DETAILS_SELECT +
        " WHERE (:start IS NULL OR t.dateTime >= :start)" +
        " AND (:end IS NULL OR t.dateTime < :end)" +
        " AND (:type IS NULL OR t.type = :type)" +
        " AND (:accountId IS NULL OR t.accountId = :accountId OR t.toAccountId = :accountId)" +
        " AND (:categoryId IS NULL OR t.categoryId = :categoryId OR c.parentId = :categoryId)" +
        " AND (:favoritesOnly = 0 OR t.isFavorite = 1)" +
        DETAILS_ORDER
}
