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

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
