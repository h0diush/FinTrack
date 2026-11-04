package app.fintrack.domain.logic

import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Чтение CSV: кодировка, разделитель, кавычки. */
object CsvReader {

    /** UTF-8 (с BOM или без) или UTF-16 по BOM; всё, что не UTF-8, считается windows-1251 — так выгружают банки. */
    fun decode(bytes: ByteArray): String {
        fun has(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
        return when {
            has(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            has(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            has(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1251"))
            }
        }
    }

    /** Разделитель, дающий больше всего одинаковых по числу колонок строк. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(50).joinToString("\n")
        return listOf(';', '\t', ',', '|').maxByOrNull { d ->
            val counts = parse(sample, d).map { it.size }.filter { it > 1 }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            (common?.value ?: 0) * 100 + (common?.key ?: 0)
        } ?: ';'
    }

    /** Разбор с кавычками: разделитель и перевод строки внутри "…" — часть значения, "" — кавычка. */
    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() {
            row.add(cell.toString().trim())
            cell.setLength(0)
        }
        fun endRow() {
            endCell()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> cell.append(c)
                c == delimiter -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

/** Номера колонок выписки; null — такой колонки нет. */
data class ColumnMap(
    val date: Int? = null,
    /** Одна колонка суммы со знаком… */
    val amount: Int? = null,
    /** …или две отдельные: приход и расход. */
    val credit: Int? = null,
    val debit: Int? = null,
    val description: Int? = null,
    val category: Int? = null,
    val subcategory: Int? = null,
    val comment: Int? = null,
    val currency: Int? = null,
    val type: Int? = null,
) {
    val hasAmount: Boolean get() = amount != null || credit != null || debit != null
    val isValid: Boolean get() = date != null && hasAmount
}

/** Как понимать знак в колонке суммы. */
enum class SignRule(val title: String) {
    SIGNED("Минус — расход, плюс — доход"),
    INVERTED("Плюс — расход, минус — доход"),
    ALL_EXPENSES("Все суммы — расходы"),
    ALL_INCOME("Все суммы — доходы"),
}

data class StatementRow(
    /** Номер строки в файле с единицы — для сообщений. */
    val line: Int,
    val dateTime: LocalDateTime,
    val isIncome: Boolean,
    /** Всегда больше нуля. */
    val amount: BigDecimal,
    /** Валюта из файла; null — валюта счёта. */
    val currency: String?,
    val description: String,
    val comment: String,
    val category: String?,
    val subcategory: String?,
)

data class ParsedStatement(
    val rows: List<StatementRow>,
    /** Строки, где не разобрать дату или сумму. */
    val errorLines: List<Int>,
    /** Переводы и корректировки из нашего же экспорта — импорт только доходов и расходов. */
    val skippedTransfers: Int,
)

object StatementParser {

    private val DATE_TIME = listOf(
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd.MM.yy HH:mm",
    ).map(DateTimeFormatter::ofPattern)
    private val DATE_ONLY = listOf("dd.MM.yyyy", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yy").map(DateTimeFormatter::ofPattern)

    /** Дата без времени считается полднем — операция не сползёт на другой день. */
    fun parseDate(text: String): LocalDateTime? {
        val t = text.trim().replace(Regex("""\s+"""), " ").removeSuffix("Z")
        if (t.isEmpty()) return null
        DATE_TIME.forEach { f ->
            try {
                return LocalDateTime.parse(t, f)
            } catch (e: DateTimeParseException) {
                // следующий формат
            }
        }
        DATE_ONLY.forEach { f ->
            try {
                return LocalDate.parse(t, f).atTime(LocalTime.NOON)
            } catch (e: DateTimeParseException) {
                // следующий формат
            }
        }
        return null
    }

    /**
     * Сумма со знаком: «1 234,56», «1,234.56», «-12.50», «−12,50», «(12,50)», «12,50-», «12,50 BYN».
     * Десятичный разделитель — последний из точки и запятой, если после него 1–2 цифры (или один вид и не группы по 3).
     */
    fun parseAmount(text: String): BigDecimal? {
        var t = text.trim().replace(' ', ' ').replace('−', '-').replace('–', '-')
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true
            t = t.substring(1, t.length - 1)
        }
        t = t.filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
        if (t.endsWith("-")) {
            negative = true
            t = t.dropLast(1)
        }
        if (t.startsWith("-")) {
            negative = !negative
            t = t.drop(1)
        } else if (t.startsWith("+")) {
            t = t.drop(1)
        }
        if (t.isEmpty() || t.none { it.isDigit() } || t.any { it == '-' || it == '+' }) return null
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val sepIndex = maxOf(lastDot, lastComma)
        val plain = if (sepIndex < 0) {
            t
        } else {
            val sep = t[sepIndex]
            val tail = t.length - sepIndex - 1
            val sameSepCount = t.count { it == sep }
            val otherPresent = if (sep == '.') lastComma >= 0 else lastDot >= 0
            // «1,234» и «1.234.567» — группы разрядов, а не копейки
            val isGroup = !otherPresent && (sameSepCount > 1 || tail == 3)
            if (isGroup) {
                t.filter { it.isDigit() }
            } else {
                t.substring(0, sepIndex).filter { it.isDigit() } + "." + t.substring(sepIndex + 1)
            }
        }
        val v = plain.toBigDecimalOrNull() ?: return null
        return if (negative) v.negate() else v
    }

    private fun looksLikeData(cell: String) = parseDate(cell) != null || (cell.any { it.isDigit() } && parseAmount(cell) != null)

    /**
     * Строка заголовков: первая с «обычным» числом колонок, где большинство ячеек — слова.
     * Банки часто пишут сверху реквизиты счёта и период.
     */
    fun headerRow(rows: List<List<String>>): Int {
        if (rows.isEmpty()) return 0
        val width = rows.take(200).groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return 0
        val index = rows.indexOfFirst { r ->
            r.size == width && r.count { it.isNotBlank() && !looksLikeData(it) } * 2 >= r.count { it.isNotBlank() } && r.count { it.isNotBlank() } >= 2
        }
        return index.coerceAtLeast(0)
    }

    /** Угадать колонки по названиям (русские и английские, в том числе наш экспорт). */
    fun guess(headers: List<String>): ColumnMap {
        val h = headers.map { it.lowercase().trim().removePrefix("﻿") }
        fun find(vararg words: String, except: List<String> = emptyList()): Int? =
            h.indexOfFirst { name -> words.any { it in name } && except.none { it in name } }.takeIf { it >= 0 }
        val credit = find("приход", "зачислен", "поступлен", "кредит", "credit", except = listOf("сумма зачисления", "счёт", "счет"))
        val debit = find("расход", "списан", "дебет", "debit", except = listOf("счёт", "счет"))
        val split = credit != null && debit != null && credit != debit
        return ColumnMap(
            date = find("дата операции", "дата транзакции", "дата совершения") ?: find("дата", "date", "время", except = listOf("обработ", "рождения")),
            amount = if (split) null else find("сумма в валюте счета", "сумма в валюте счёта", "сумма операции")
                ?: find("сумма", "amount", "sum", except = listOf("зачислен", "исходн", "комисс")),
            credit = if (split) credit else null,
            debit = if (split) debit else null,
            description = find("получатель", "контрагент", "назначени", "описание", "наименование", "место", "description", "merchant", "payee", "детали"),
            category = find("категория", "category", "mcc", except = listOf("подкатег")),
            subcategory = find("подкатегория"),
            comment = find("комментарий", "примечание", "comment", "note"),
            currency = find("валюта счета", "валюта счёта") ?: find("валюта", "currency", except = listOf("зачислен", "исходн")),
            type = find("тип", "вид операции", "type"),
        )
    }

    /** Разобрать строки ниже [headerIndex]. [rows] — весь файл. */
    fun parse(rows: List<List<String>>, headerIndex: Int, map: ColumnMap, sign: SignRule): ParsedStatement {
        val result = ArrayList<StatementRow>()
        val errors = ArrayList<Int>()
        var skipped = 0
        fun List<String>.at(i: Int?) = i?.let { getOrNull(it) }.orEmpty().trim().removePrefix("'")
        for (index in (headerIndex + 1) until rows.size) {
            val r = rows[index]
            val line = index + 1
            val typeText = r.at(map.type).lowercase()
            if (typeText.startsWith("перевод") || typeText.startsWith("корректировк")) {
                skipped++
                continue
            }
            val date = parseDate(r.at(map.date))
            val signed: BigDecimal? = if (map.amount != null) {
                parseAmount(r.at(map.amount))
            } else {
                val inc = parseAmount(r.at(map.credit))?.abs()?.takeIf { it.signum() > 0 }
                val exp = parseAmount(r.at(map.debit))?.abs()?.takeIf { it.signum() > 0 }
                inc ?: exp?.negate()
            }
            // Нулевые суммы (авторизации, запросы баланса) просто пропускаются
            if (signed != null && signed.signum() == 0) continue
            if (date == null || signed == null) {
                // Итоговые строки выписки («Итого», «Остаток») без даты и суммы — не ошибка, а конец таблицы
                if (date != null || signed != null) errors += line
                continue
            }
            val isIncome = when {
                map.amount == null -> signed.signum() > 0
                typeText.startsWith("расход") -> false
                typeText.startsWith("доход") -> true
                sign == SignRule.ALL_EXPENSES -> false
                sign == SignRule.ALL_INCOME -> true
                sign == SignRule.INVERTED -> signed.signum() < 0
                else -> signed.signum() > 0
            }
            result += StatementRow(
                line = line,
                dateTime = date,
                isIncome = isIncome,
                amount = signed.abs(),
                currency = r.at(map.currency).uppercase().takeIf { it.matches(Regex("[A-Z]{3}")) },
                description = r.at(map.description),
                comment = r.at(map.comment),
                category = r.at(map.category).ifBlank { null },
                subcategory = r.at(map.subcategory).ifBlank { null },
            )
        }
        return ParsedStatement(result, errors, skipped)
    }
}

/** Защита от дублей при импорте. */
object ImportDedup {

    /**
     * Постоянный id операции: одна и та же строка той же выписки всегда даёт тот же id, поэтому
     * повторная загрузка файла ничего не задвоит. Две одинаковые покупки за день различаются номером.
     */
    fun ids(accountId: String, rows: List<StatementRow>): List<String> {
        val seen = HashMap<String, Int>()
        return rows.map { r ->
            val base = listOf(accountId, r.dateTime, r.isIncome, r.amount.stripTrailingZeros().toPlainString(), r.currency, r.description).joinToString("|")
            val n = seen.merge(base, 1, Int::plus)!!
            val hash = MessageDigest.getInstance("SHA-256").digest("$base|$n".toByteArray())
            "imp:" + hash.take(16).joinToString("") { "%02x".format(it) }
        }
    }

    /** Ключ для поиска «похожей» операции, внесённой вручную: тот же день, сумма и направление. */
    fun similarKey(day: LocalDate, amountMinor: Long, isIncome: Boolean) = "$day|$amountMinor|$isIncome"
}

/** Подбор категории для строки выписки. */
object CategoryGuesser {

    /** Слова в описании → ключ иконки стартовой категории (ключи в БД не меняются). */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "groceries" to listOf("евроопт", "евроторг", "гиппо", "корона", "соседи", "санта", "алми", "виталюр", "green", "magnit", "магнит", "пятерочка", "перекресток", "лента", "продукт", "market", "supermarket"),
        "restaurant" to listOf("кафе", "ресторан", "cafe", "coffee", "кофе", "mcdonald", "макдоналдс", "kfc", "burger", "пицца", "pizza", "суши", "sushi", "доставка еды", "wolt", "glovo"),
        "bus" to listOf("метро", "автобус", "транспорт", "такси", "yandex go", "uber", "bolt", "яндекс go", "white wall"),
        "car" to listOf("азс", "белоруснефть", "лукойл", "a-100", "gazprom", "газпром", "парковка", "шиномонтаж", "автомойка"),
        "home" to listOf("жкх", "коммунал", "ерип жкх", "электроэнерг", "газоснабж", "водоканал", "квартплат"),
        "health" to listOf("аптека", "pharmacy", "клиника", "стоматолог", "медцентр", "лаборатор"),
        "clothes" to listOf("одежд", "обув", "zara", "h&m", "lc waikiki", "mark formelle", "wildberries одежда"),
        "shopping" to listOf("wildberries", "ozon", "21vek", "21 век", "onliner", "aliexpress", "магазин", "shop"),
        "subscriptions" to listOf("netflix", "spotify", "подписк", "subscription", "apple.com", "google one", "youtube", "кинопоиск"),
        "wifi" to listOf("интернет", "byfly", "мтс", "life:)", "белтелеком"),
        "flight" to listOf("авиа", "aviasales", "belavia", "ryanair", "booking", "отель", "hotel"),
        "games" to listOf("кино", "cinema", "steam", "playstation", "театр", "боулинг"),
        "school" to listOf("обучени", "школа", "университет", "учебн"),
        "credit" to listOf("погашение кредит", "платеж по кредит", "платёж по кредит"),
    )

    private val INCOME_KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "salary" to listOf("зарплат", "заработн", "оплата труда", "salary"),
        "invest" to listOf("проценты", "капитализац", "дивиденд"),
        "refund" to listOf("возврат", "refund", "кэшбэк", "cashback"),
    )

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("""\d{3,}"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * Порядок: категория из файла (наш экспорт, банк) → как вы уже относили такого получателя → слова
     * в описании → «Прочее». [learned]: нормализованное описание → id категории из ваших операций.
     */
    fun guess(row: StatementRow, categories: List<Category>, learned: Map<String, String>): Category? {
        val kind = if (row.isIncome) CategoryKind.INCOME else CategoryKind.EXPENSE
        val active = categories.filter { it.kind == kind && !it.isArchived }
        val roots = active.filter { it.parentId == null }
        fun byName(name: String?, among: List<Category>) = name?.let { n -> among.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

        val root = byName(row.category, roots)
        if (root != null) {
            return byName(row.subcategory, active.filter { it.parentId == root.id }) ?: root
        }
        byName(row.category, active)?.let { return it }
        learned[normalize(row.description)]?.let { id -> active.firstOrNull { it.id == id } }?.let { return it }

        val text = (row.description + " " + (row.category ?: "")).lowercase()
        val table = if (row.isIncome) INCOME_KEYWORDS else KEYWORDS
        table.firstOrNull { (_, words) -> words.any { it in text } }?.let { (icon, _) ->
            roots.firstOrNull { it.iconKey == icon }?.let { return it }
        }
        return roots.firstOrNull { it.isSystem } ?: roots.firstOrNull { it.iconKey == "other" } ?: roots.firstOrNull()
    }
}
