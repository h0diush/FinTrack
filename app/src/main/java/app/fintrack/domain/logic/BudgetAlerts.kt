package app.fintrack.domain.logic

import java.time.YearMonth

/** Ключ бюджета в уведомлениях: общий бюджет месяца или id категории. */
const val OVERALL_BUDGET_KEY = "overall"

/** Запомненные уровни бюджетов за месяц (что уже было показано). */
data class BudgetAlertState(val month: YearMonth, val levels: Map<String, BudgetLevel>) {

    /** «overall=WARNING;cat1=INFO» — для хранения в настройках. */
    fun encode(): String = levels.entries.joinToString(";") { "${it.key}=${it.value.name}" }

    companion object {
        fun decode(month: YearMonth, text: String?): BudgetAlertState = BudgetAlertState(
            month,
            text.orEmpty().split(';').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                val level = BudgetLevel.entries.firstOrNull { it.name == part.substringAfter('=', "") }
                if (key.isEmpty() || level == null) null else key to level
            }.toMap(),
        )
    }
}

/** Что показать: бюджет [key] поднялся до уровня [level]. */
data class BudgetAlert(val key: String, val level: BudgetLevel)

object BudgetAlerts {

    /**
     * Сравнить текущие уровни с запомненными.
     * Уведомление — только когда уровень бюджета вырос (50 → 80 → 100 %); если сразу перескочил
     * через несколько порогов — одно уведомление о самом высоком. Снижение (удалили расход,
     * подняли лимит) запоминается, и следующий рост снова будет замечен.
     *
     * @param saved состояние с прошлой проверки; null — проверок ещё не было (первый запуск
     *   этой функции): текущее положение запоминается без уведомлений.
     * @return уведомления и новое состояние для сохранения.
     */
    fun evaluate(
        saved: BudgetAlertState?,
        month: YearMonth,
        current: Map<String, BudgetLevel>,
    ): Pair<List<BudgetAlert>, BudgetAlertState> {
        val next = BudgetAlertState(month, current.filterValues { it != BudgetLevel.NORMAL })
        if (saved == null) return emptyList<BudgetAlert>() to next
        // Новый месяц — всё начинается с нуля.
        val before = if (saved.month == month) saved.levels else emptyMap()
        val alerts = current.mapNotNull { (key, level) ->
            val was = before[key] ?: BudgetLevel.NORMAL
            if (level != BudgetLevel.NORMAL && level.ordinal > was.ordinal) BudgetAlert(key, level) else null
        }
        // Сначала общий бюджет, затем самые серьёзные.
        val sorted = alerts.sortedWith(compareBy<BudgetAlert> { it.key != OVERALL_BUDGET_KEY }.thenByDescending { it.level.ordinal })
        return sorted to next
    }
}
