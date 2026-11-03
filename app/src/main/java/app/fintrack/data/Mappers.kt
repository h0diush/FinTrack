package app.fintrack.data

import app.fintrack.data.db.AccountEntity
import app.fintrack.data.db.CategoryEntity
import app.fintrack.data.db.TransactionDetailsRow
import app.fintrack.data.db.TransactionEntity
import app.fintrack.domain.model.Account
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.Transaction
import app.fintrack.domain.model.TransactionDetails

fun AccountEntity.toDomain() = Account(
    id = id, name = name, type = type, currencyCode = currencyCode,
    initialBalanceMinor = initialBalanceMinor, color = color, sortOrder = sortOrder,
    isArchived = isArchived, createdAt = createdAt, excludeFromTotal = excludeFromTotal,
)

fun Account.toEntity() = AccountEntity(
    id = id, name = name, type = type, currencyCode = currencyCode,
    initialBalanceMinor = initialBalanceMinor, color = color, sortOrder = sortOrder,
    isArchived = isArchived, createdAt = createdAt, excludeFromTotal = excludeFromTotal,
)

fun CategoryEntity.toDomain() = Category(
    id = id, name = name, kind = kind, parentId = parentId, iconKey = iconKey,
    color = color, sortOrder = sortOrder, isArchived = isArchived, isSystem = isSystem,
)

fun Category.toEntity() = CategoryEntity(
    id = id, name = name, kind = kind, parentId = parentId, iconKey = iconKey,
    color = color, sortOrder = sortOrder, isArchived = isArchived, isSystem = isSystem,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id, type = type, accountId = accountId, toAccountId = toAccountId,
    amountMinor = amountMinor, toAmountMinor = toAmountMinor, feeMinor = feeMinor,
    categoryId = categoryId, dateTime = dateTime, payee = payee, comment = comment,
    isFavorite = isFavorite, createdAt = createdAt, updatedAt = updatedAt,
    originalAmountMinor = originalAmountMinor, originalCurrency = originalCurrency,
)

fun Transaction.toEntity() = TransactionEntity(
    id = id, type = type, accountId = accountId, toAccountId = toAccountId,
    amountMinor = amountMinor, toAmountMinor = toAmountMinor, feeMinor = feeMinor,
    categoryId = categoryId, dateTime = dateTime, payee = payee, comment = comment,
    isFavorite = isFavorite, createdAt = createdAt, updatedAt = updatedAt,
    originalAmountMinor = originalAmountMinor, originalCurrency = originalCurrency,
)

fun TransactionDetailsRow.toDomain() = TransactionDetails(
    transaction = tx.toDomain(),
    accountName = accountName,
    accountCurrency = accountCurrency,
    toAccountName = toAccountName,
    toAccountCurrency = toAccountCurrency,
    categoryName = categoryName,
    categoryIcon = categoryIcon,
    categoryColor = categoryColor,
    parentCategoryName = parentCategoryName,
)
