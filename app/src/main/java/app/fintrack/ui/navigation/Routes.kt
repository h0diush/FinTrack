package app.fintrack.ui.navigation

import android.net.Uri

object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val ACCOUNTS = "accounts"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val BUDGET = "budget"
    const val CATEGORIES = "categories"

    const val EDITOR = "editor?type={type}&id={id}&copyOf={copyOf}&recurringId={recurringId}&repeat={repeat}"
    const val ACCOUNT_EDIT = "account_edit?id={id}"
    const val ACCOUNT_HISTORY = "account_history/{accountId}"
    const val CATEGORY_EDIT = "category_edit?id={id}&kind={kind}&parentId={parentId}"
    const val PIGGY = "piggy/{id}"
    const val PIGGY_EDIT = "piggy_edit?id={id}"
    const val RECURRING = "recurring"
    const val RATES = "rates"
    const val LOANS = "loans"
    const val LOAN = "loan/{id}"
    const val LOAN_EDIT = "loan_edit?id={id}&kind={kind}"

    /** Вкладки нижней панели (по ТЗ). Счета и настройки открываются с главной. */
    val tabs = setOf(HOME, HISTORY, STATS, BUDGET)

    private fun query(vararg pairs: Pair<String, String?>): String =
        pairs.filter { it.second != null }
            .joinToString("&") { "${it.first}=${Uri.encode(it.second)}" }
            .let { if (it.isEmpty()) "" else "?$it" }

    fun editor(type: String? = null, id: String? = null, copyOf: String? = null) =
        "editor" + query("type" to type, "id" to id, "copyOf" to copyOf)

    /** Изменить регулярный платёж. */
    fun recurringEdit(id: String) = "editor" + query("recurringId" to id)

    /** Новый регулярный платёж: форма операции с включённым повтором. */
    fun recurringNew(type: String? = null) = "editor" + query("type" to type, "repeat" to "1")

    fun accountEdit(id: String? = null) = "account_edit" + query("id" to id)

    fun accountHistory(accountId: String) = "account_history/${Uri.encode(accountId)}"

    fun piggy(id: String) = "piggy/${Uri.encode(id)}"

    fun piggyEdit(id: String? = null) = "piggy_edit" + query("id" to id)

    fun loan(id: String) = "loan/${Uri.encode(id)}"

    fun loanEdit(id: String? = null, kind: String? = null) = "loan_edit" + query("id" to id, "kind" to kind)

    fun categoryEdit(id: String? = null, kind: String? = null, parentId: String? = null) =
        "category_edit" + query("id" to id, "kind" to kind, "parentId" to parentId)
}
