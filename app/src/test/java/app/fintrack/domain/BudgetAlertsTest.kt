package app.fintrack.domain

import app.fintrack.domain.logic.BudgetAlert
import app.fintrack.domain.logic.BudgetAlertState
import app.fintrack.domain.logic.BudgetAlerts
import app.fintrack.domain.logic.BudgetLevel.EXCEEDED
import app.fintrack.domain.logic.BudgetLevel.INFO
import app.fintrack.domain.logic.BudgetLevel.NORMAL
import app.fintrack.domain.logic.BudgetLevel.WARNING
import app.fintrack.domain.logic.OVERALL_BUDGET_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class BudgetAlertsTest {

    private val nov = YearMonth.of(2026, 11)

    @Test
    fun firstRunRemembersSilently() {
        val (alerts, state) = BudgetAlerts.evaluate(null, nov, mapOf("food" to WARNING, OVERALL_BUDGET_KEY to NORMAL))
        assertTrue(alerts.isEmpty())
        assertEquals(mapOf("food" to WARNING), state.levels)
    }

    @Test
    fun notifiesOnlyWhenLevelRises() {
        val saved = BudgetAlertState(nov, mapOf("food" to INFO))
        val (alerts, state) = BudgetAlerts.evaluate(saved, nov, mapOf("food" to WARNING, "fun" to INFO, "car" to NORMAL))
        assertEquals(setOf(BudgetAlert("food", WARNING), BudgetAlert("fun", INFO)), alerts.toSet())
        // Повторная проверка без изменений — тишина
        assertTrue(BudgetAlerts.evaluate(state, nov, mapOf("food" to WARNING, "fun" to INFO)).first.isEmpty())
    }

    @Test
    fun jumpOverThresholdsGivesOneAlert() {
        val (alerts, _) = BudgetAlerts.evaluate(BudgetAlertState(nov, emptyMap()), nov, mapOf("food" to EXCEEDED))
        assertEquals(listOf(BudgetAlert("food", EXCEEDED)), alerts)
    }

    @Test
    fun dropThenRiseNotifiesAgain() {
        var state = BudgetAlertState(nov, mapOf("food" to EXCEEDED))
        // Лимит подняли — уровень упал
        state = BudgetAlerts.evaluate(state, nov, mapOf("food" to INFO)).second
        val (alerts, _) = BudgetAlerts.evaluate(state, nov, mapOf("food" to EXCEEDED))
        assertEquals(listOf(BudgetAlert("food", EXCEEDED)), alerts)
    }

    @Test
    fun newMonthStartsFromScratch() {
        val saved = BudgetAlertState(nov, mapOf("food" to WARNING))
        val (alerts, state) = BudgetAlerts.evaluate(saved, nov.plusMonths(1), mapOf("food" to INFO))
        assertEquals(listOf(BudgetAlert("food", INFO)), alerts)
        assertEquals(nov.plusMonths(1), state.month)
    }

    @Test
    fun overallFirstThenBySeverity() {
        val (alerts, _) = BudgetAlerts.evaluate(
            BudgetAlertState(nov, emptyMap()),
            nov,
            mapOf("a" to INFO, "b" to EXCEEDED, OVERALL_BUDGET_KEY to INFO),
        )
        assertEquals(listOf(OVERALL_BUDGET_KEY, "b", "a"), alerts.map { it.key })
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val s = BudgetAlertState(nov, mapOf(OVERALL_BUDGET_KEY to WARNING, "c-1" to EXCEEDED))
        assertEquals(s, BudgetAlertState.decode(nov, s.encode()))
        assertEquals(emptyMap<String, Any>(), BudgetAlertState.decode(nov, "мусор;=;x=BAD").levels)
    }
}
