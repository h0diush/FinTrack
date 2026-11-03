package app.fintrack.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fintrack.domain.logic.CurrencyConverter
import app.fintrack.domain.logic.ExchangeRate
import app.fintrack.domain.logic.NbrbParser
import app.fintrack.domain.logic.RateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Курсы и когда их последний раз загружали. */
data class RatesState(
    /** Действующие курсы: ручной, если задан, иначе НБРБ. */
    val rates: Map<String, ExchangeRate> = emptyMap(),
    /** Курсы НБРБ отдельно — чтобы показать их рядом с ручным. */
    val nbrb: Map<String, ExchangeRate> = emptyMap(),
    val updatedAt: Long? = null,
) {
    val converter: CurrencyConverter get() = CurrencyConverter(rates.mapValues { it.value.bynPerUnit })
}

enum class RatesRefreshResult(val message: String) {
    UPDATED("Курсы НБРБ обновлены"),
    FRESH("Курсы уже свежие"),
    OFFLINE("Нет связи с НБРБ — используются сохранённые курсы"),
}

/**
 * Курсы валют. Источник — официальные курсы Нацбанка РБ (api.nbrb.by), загружаются не чаще
 * раза в несколько часов; любой курс можно задать вручную — он важнее курса НБРБ.
 * В сеть уходит только запрос курсов, данные пользователя никуда не передаются.
 * Хранится в DataStore строками «USD=3.2741@2026-11-03;…».
 */
@Singleton
class RatesRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val NBRB = stringPreferencesKey("rates_nbrb")
        val MANUAL = stringPreferencesKey("rates_manual")
        val UPDATED_AT = longPreferencesKey("rates_updated_at")
    }

    private val refreshLock = Mutex()

    val state: Flow<RatesState> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val nbrb = decode(p[Keys.NBRB], RateSource.NBRB)
            val manual = decode(p[Keys.MANUAL], RateSource.MANUAL)
            RatesState(rates = nbrb + manual, nbrb = nbrb, updatedAt = p[Keys.UPDATED_AT])
        }

    /** Загрузить курсы НБРБ, если с прошлой загрузки прошло больше [maxAgeMs] (или [force]). */
    suspend fun refresh(force: Boolean = false, maxAgeMs: Long = 6 * 60 * 60 * 1000L): RatesRefreshResult = refreshLock.withLock {
        val last = state.first().updatedAt
        if (!force && last != null && System.currentTimeMillis() - last < maxAgeMs) return RatesRefreshResult.FRESH
        val json = withContext(Dispatchers.IO) { runCatching { download() }.getOrNull() } ?: return RatesRefreshResult.OFFLINE
        val parsed = NbrbParser.parse(json)
        if (parsed.isEmpty()) return RatesRefreshResult.OFFLINE
        val rates = parsed.associate { it.code to ExchangeRate(it.code, it.bynPerUnit, RateSource.NBRB, it.date) }
        store.edit {
            it[Keys.NBRB] = encode(rates.values)
            it[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
        RatesRefreshResult.UPDATED
    }

    /** Задать курс вручную: сколько BYN стоит 1 единица валюты. */
    suspend fun setManual(currency: String, bynPerUnit: BigDecimal) {
        store.edit { p ->
            val manual = decode(p[Keys.MANUAL], RateSource.MANUAL) +
                (currency to ExchangeRate(currency, bynPerUnit, RateSource.MANUAL, LocalDate.now()))
            p[Keys.MANUAL] = encode(manual.values)
        }
    }

    /** Убрать ручной курс — снова действует курс НБРБ. */
    suspend fun clearManual(currency: String) {
        store.edit { p ->
            p[Keys.MANUAL] = encode((decode(p[Keys.MANUAL], RateSource.MANUAL) - currency).values)
        }
    }

    private fun download(): String {
        val conn = URL(NBRB_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(rates: Collection<ExchangeRate>): String =
        rates.joinToString(";") { "${it.currency}=${it.bynPerUnit.toPlainString()}@${it.date}" }

    private fun decode(text: String?, source: RateSource): Map<String, ExchangeRate> =
        text.orEmpty().split(';').mapNotNull { part ->
            val code = part.substringBefore('=', "").takeIf { it.length == 3 } ?: return@mapNotNull null
            val value = part.substringAfter('=').substringBefore('@').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                ?: return@mapNotNull null
            val date = runCatching { LocalDate.parse(part.substringAfter('@')) }.getOrDefault(LocalDate.now())
            code to ExchangeRate(code, value, source, date)
        }.toMap()

    companion object {
        /** Официальные курсы на сегодня, ежедневно устанавливаемые валюты. */
        private const val NBRB_URL = "https://api.nbrb.by/exrates/rates?periodicity=0"
    }
}
