package app.fintrack.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Миграции схемы. Каждая — точный SQL, совпадающий с тем, что генерирует Room
 * для сущностей (Room проверяет схему при открытии базы). Данные не теряются.
 */
object Migrations {

    /** 1 → 2: лимиты бюджета. */
    const val CREATE_BUDGET_LIMITS = "CREATE TABLE IF NOT EXISTS `budget_limits` (" +
        "`id` TEXT NOT NULL, `categoryId` TEXT, `currencyCode` TEXT NOT NULL, " +
        "`amountMinor` INTEGER NOT NULL, `rollover` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, " +
        "`endMonth` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    const val CREATE_BUDGET_LIMITS_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_budget_limits_categoryId` ON `budget_limits` (`categoryId`)"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_BUDGET_LIMITS)
            db.execSQL(CREATE_BUDGET_LIMITS_INDEX)
        }
    }

    /** 2 → 3: вклады на накопительных счетах. */
    const val CREATE_DEPOSITS = "CREATE TABLE IF NOT EXISTS `deposits` (" +
        "`accountId` TEXT NOT NULL, `rateBp` INTEGER NOT NULL, `startDay` INTEGER NOT NULL, " +
        "`termMonths` INTEGER NOT NULL, `interestCategoryId` TEXT, `postedMonths` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), " +
        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_DEPOSITS)
        }
    }

    /** 3 → 4: цели копилок. Сами копилки — счета с типом PIGGY (тип хранится текстом). */
    const val CREATE_PIGGY_BANKS = "CREATE TABLE IF NOT EXISTS `piggy_banks` (" +
        "`accountId` TEXT NOT NULL, `targetMinor` INTEGER NOT NULL, `iconKey` TEXT NOT NULL, " +
        "`deadlineDay` INTEGER, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), " +
        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_PIGGY_BANKS)
        }
    }

    /** 4 → 5: счёт можно исключить из общего баланса. Существующие счета остаются в балансе. */
    const val ADD_EXCLUDE_FROM_TOTAL =
        "ALTER TABLE `accounts` ADD COLUMN `excludeFromTotal` INTEGER NOT NULL DEFAULT 0"

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ADD_EXCLUDE_FROM_TOTAL)
        }
    }

    /** 5 → 6: условия кредитов. Сами кредиты — счета с типом LOAN (тип хранится текстом). */
    const val CREATE_LOANS = "CREATE TABLE IF NOT EXISTS `loans` (" +
        "`accountId` TEXT NOT NULL, `kind` TEXT NOT NULL, `schedule` TEXT NOT NULL, " +
        "`principalMinor` INTEGER NOT NULL, `rateBp` INTEGER NOT NULL, `startDay` INTEGER NOT NULL, " +
        "`termMonths` INTEGER NOT NULL, `initialPaid` INTEGER NOT NULL, `interestCategoryId` TEXT, " +
        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), " +
        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_LOANS)
        }
    }

    /** 6 → 7: регулярные платежи (шаблоны). Проведённые повторы — обычные операции. */
    const val CREATE_RECURRING = "CREATE TABLE IF NOT EXISTS `recurring` (" +
        "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `accountId` TEXT NOT NULL, `toAccountId` TEXT, " +
        "`amountMinor` INTEGER NOT NULL, `toAmountMinor` INTEGER, `feeMinor` INTEGER NOT NULL, `categoryId` TEXT, " +
        "`payee` TEXT NOT NULL, `comment` TEXT NOT NULL, `unit` TEXT NOT NULL, `repeatInterval` INTEGER NOT NULL, " +
        "`startDay` INTEGER NOT NULL, `lastDayOfMonth` INTEGER NOT NULL, `endDay` INTEGER, `maxCount` INTEGER, " +
        "`minuteOfDay` INTEGER NOT NULL, `processedCount` INTEGER NOT NULL, `mode` TEXT NOT NULL, " +
        "`isPaused` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`toAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"

    const val CREATE_RECURRING_INDEX_ACCOUNT =
        "CREATE INDEX IF NOT EXISTS `index_recurring_accountId` ON `recurring` (`accountId`)"
    const val CREATE_RECURRING_INDEX_TO_ACCOUNT =
        "CREATE INDEX IF NOT EXISTS `index_recurring_toAccountId` ON `recurring` (`toAccountId`)"
    const val CREATE_RECURRING_INDEX_CATEGORY =
        "CREATE INDEX IF NOT EXISTS `index_recurring_categoryId` ON `recurring` (`categoryId`)"

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_RECURRING)
            db.execSQL(CREATE_RECURRING_INDEX_ACCOUNT)
            db.execSQL(CREATE_RECURRING_INDEX_TO_ACCOUNT)
            db.execSQL(CREATE_RECURRING_INDEX_CATEGORY)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
}
