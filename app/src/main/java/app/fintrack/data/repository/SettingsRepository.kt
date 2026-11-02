package app.fintrack.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fintrack.domain.model.AppSettings
import app.fintrack.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val BASE_CURRENCY = stringPreferencesKey("base_currency")
        val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts")
        val LAST_ACCOUNT = stringPreferencesKey("last_account")
        val LAST_EXPENSE_CATEGORY = stringPreferencesKey("last_expense_category")
        val LAST_INCOME_CATEGORY = stringPreferencesKey("last_income_category")
        val BUDGET_INFO = intPreferencesKey("budget_info_percent")
        val BUDGET_WARNING = intPreferencesKey("budget_warning_percent")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val defaults = AppSettings()
            AppSettings(
                themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: defaults.themeMode,
                baseCurrency = p[Keys.BASE_CURRENCY] ?: defaults.baseCurrency,
                hideAmounts = p[Keys.HIDE_AMOUNTS] ?: defaults.hideAmounts,
                lastAccountId = p[Keys.LAST_ACCOUNT],
                lastExpenseCategoryId = p[Keys.LAST_EXPENSE_CATEGORY],
                lastIncomeCategoryId = p[Keys.LAST_INCOME_CATEGORY],
                budgetInfoPercent = p[Keys.BUDGET_INFO] ?: defaults.budgetInfoPercent,
                budgetWarningPercent = p[Keys.BUDGET_WARNING] ?: defaults.budgetWarningPercent,
            )
        }

    suspend fun setTheme(mode: ThemeMode) {
        store.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setBaseCurrency(code: String) {
        store.edit { it[Keys.BASE_CURRENCY] = code }
    }

    suspend fun setHideAmounts(hide: Boolean) {
        store.edit { it[Keys.HIDE_AMOUNTS] = hide }
    }

    suspend fun setBudgetThresholds(infoPercent: Int, warningPercent: Int) {
        store.edit {
            it[Keys.BUDGET_INFO] = infoPercent
            it[Keys.BUDGET_WARNING] = warningPercent
        }
    }

    suspend fun rememberLastUsed(accountId: String?, expenseCategoryId: String?, incomeCategoryId: String?) {
        store.edit { p ->
            accountId?.let { p[Keys.LAST_ACCOUNT] = it }
            expenseCategoryId?.let { p[Keys.LAST_EXPENSE_CATEGORY] = it }
            incomeCategoryId?.let { p[Keys.LAST_INCOME_CATEGORY] = it }
        }
    }
}
