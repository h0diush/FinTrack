@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.exchange

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.data.repository.ImportItem
import app.fintrack.data.repository.ImportStatus
import app.fintrack.domain.logic.Money
import app.fintrack.domain.logic.SignRule
import app.fintrack.domain.logic.ruPlural
import app.fintrack.domain.model.Category
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.FitText
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.Motion
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.piggy.readableOn
import app.fintrack.ui.theme.FinTheme
import java.time.format.DateTimeFormatter

private val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun operations(n: Int) = "$n ${ruPlural(n.toLong(), "операция", "операции", "операций")}"

@Composable
fun ImportScreen(onClose: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }
    val goBack = {
        if (state.step == ImportStep.PICK || state.step == ImportStep.DONE) onClose() else vm.back()
    }
    BackHandler(enabled = state.step == ImportStep.MAP || state.step == ImportStep.REVIEW) { vm.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ImportStep.PICK -> "Импорт из CSV"
                            ImportStep.MAP -> "Колонки файла"
                            ImportStep.REVIEW -> "Проверка операций"
                            ImportStep.DONE -> "Готово"
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = state.step,
            transitionSpec = { fadeIn(tween(Motion.MEDIUM_MS)).togetherWith(fadeOut(tween(Motion.SHORT_MS))) },
            label = "importStep",
            modifier = Modifier.padding(padding),
        ) { step ->
            when (step) {
                ImportStep.PICK -> PickStep(state) {
                    vm.beforePicker()
                    pick.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                }
                ImportStep.MAP -> MapStep(state, vm)
                ImportStep.REVIEW -> ReviewStep(state, vm)
                ImportStep.DONE -> DoneStep(state, onClose)
            }
        }
    }
}

@Composable
private fun PickStep(state: ImportUiState, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        IconBadge(Icons.Rounded.UploadFile, MaterialTheme.colorScheme.primary, size = 72.dp, selected = true)
        Spacer(Modifier.height(18.dp))
        Text("Загрузите выписку", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Выгрузите операции из интернет-банка в файл CSV. Приложение само найдёт колонки, подберёт категории " +
                "и не задвоит то, что уже загружено.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPick,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Выбрать файл")
            }
        }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MapStep(state: ImportUiState, vm: ImportViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(state.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

            Text("На какой счёт загрузить", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.accounts.forEach { a ->
                    FilterChip(
                        selected = a.account.id == state.accountId,
                        onClick = { vm.setAccount(a.account.id) },
                        label = { Text(a.account.name, maxLines = 1) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Строка заголовков: ${state.headerIndex + 1}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.headers.filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(onClick = { vm.moveHeader(-1) }, enabled = state.headerIndex > 0) { Icon(Icons.Rounded.Remove, "Строка выше") }
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = { vm.moveHeader(1) }) { Icon(Icons.Rounded.Add, "Строка ниже") }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ColumnPicker(ImportField.DATE, state, required = true, onPick = vm::setColumn)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Приход и расход в разных колонках", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = state.splitAmount, onCheckedChange = vm::setSplitAmount)
            }
            if (state.splitAmount) {
                ColumnPicker(ImportField.CREDIT, state, onPick = vm::setColumn)
                ColumnPicker(ImportField.DEBIT, state, onPick = vm::setColumn)
            } else {
                ColumnPicker(ImportField.AMOUNT, state, required = true, onPick = vm::setColumn)
                Text("Как понимать сумму", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SignRule.entries.forEach { rule ->
                        FilterChip(selected = state.sign == rule, onClick = { vm.setSign(rule) }, label = { Text(rule.title) })
                    }
                }
            }
            ColumnPicker(ImportField.DESCRIPTION, state, onPick = vm::setColumn)
            ColumnPicker(ImportField.CATEGORY, state, onPick = vm::setColumn)
            ColumnPicker(ImportField.COMMENT, state, onPick = vm::setColumn)
            ColumnPicker(ImportField.CURRENCY, state, onPick = vm::setColumn)

            ParsePreview(state)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = vm::review,
            enabled = state.canContinue && !state.busy,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(
                when {
                    !state.map.isValid -> "Укажите дату и сумму"
                    state.parsed.rows.isEmpty() -> "Операции не распознаны"
                    else -> "Далее: ${operations(state.parsed.rows.size)}"
                },
            )
        }
    }
}

/** Выбор колонки: название и пример значения из файла. */
@Composable
private fun ColumnPicker(field: ImportField, state: ImportUiState, required: Boolean = false, onPick: (ImportField, Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.column(field)
    val missing = required && selected == null
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .bounceClick(pressedScale = 0.98f, onClickLabel = "Выбрать колонку") { open = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    field.title + if (required) " *" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.let { state.headers.getOrNull(it)?.ifBlank { "Колонка ${it + 1}" } } ?: "Не выбрано",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected != null) {
                    Text(
                        state.sample(selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!required) {
                DropdownMenuItem(text = { Text("Не использовать") }, onClick = { open = false; onPick(field, null) })
            }
            state.headers.forEachIndexed { i, h ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(h.ifBlank { "Колонка ${i + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.sample(i), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { open = false; onPick(field, i) },
                )
            }
        }
    }
}

/** Живой предпросмотр: как файл понят при текущих настройках. */
@Composable
private fun ParsePreview(state: ImportUiState) {
    val p = state.parsed
    val fin = FinTheme.colors
    val surface = MaterialTheme.colorScheme.surfaceContainer
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Распознано: ${operations(p.rows.size)}", style = MaterialTheme.typography.titleSmall)
            p.rows.take(3).forEach { r ->
                Row {
                    Text(
                        r.dateTime.format(ROW_DATE) + "  " + r.description.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (r.isIncome) "+" else "−") + r.amount.toPlainString().replace('.', ','),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (r.isIncome) fin.income.readableOn(surface) else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (p.errorLines.isNotEmpty()) {
                Text(
                    "Не разобраны строки: ${p.errorLines.take(8).joinToString()}" + if (p.errorLines.size > 8) " и ещё ${p.errorLines.size - 8}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = fin.statusWarning.readableOn(surface),
                )
            }
            if (p.skippedTransfers > 0) {
                Text(
                    "Переводы и корректировки пропущены: ${p.skippedTransfers}. Загружаются только доходы и расходы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReviewStep(state: ImportUiState, vm: ImportViewModel) {
    val cur = state.account?.account?.currencyCode.orEmpty()
    val list = state.toImport
    val income = list.filter { it.row.isIncome }.sumOf { it.amountMinor }
    val expense = list.filter { !it.row.isIncome }.sumOf { it.amountMinor }
    val similar = state.count(ImportStatus.SIMILAR)
    val already = state.count(ImportStatus.ALREADY_IMPORTED)
    val noRate = state.count(ImportStatus.NO_RATE)
    var editing by remember { mutableStateOf<ImportItem?>(null) }
    val fin = FinTheme.colors

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Будет загружено: ${operations(list.size)}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "На счёт «${state.account?.account?.name.orEmpty()}»: доходы ${Money.format(income, cur)}, расходы ${Money.format(expense, cur)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Баланс счёта изменится на ${Money.format(income - expense, cur, forceSign = true)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (already > 0) {
                            Text(
                                "Уже загружены раньше и пропущены: $already",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (noRate > 0) {
                            Text(
                                "Пропущены из-за отсутствия курса валюты: $noRate. Задайте курс в настройках и загрузите файл снова",
                                style = MaterialTheme.typography.bodySmall,
                                color = fin.statusWarning.readableOn(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }
            }
            if (similar > 0) {
                item {
                    AppCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Похожие операции: $similar", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Такая же сумма в этот день уже внесена вручную. Включите, если это разные операции",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = state.includeSimilar, onCheckedChange = vm::setIncludeSimilar)
                        }
                    }
                }
            }
            item {
                SectionTitle("Операции")
                Text(
                    "Нажмите на категорию, чтобы изменить. Новая категория применится ко всем строкам этого получателя",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(state.items, key = { it.id }) { item ->
                val included = item.status == ImportStatus.NEW || (state.includeSimilar && item.status == ImportStatus.SIMILAR)
                ImportRow(item, cur, included, Modifier.padding(horizontal = 16.dp)) { editing = item }
            }
        }
        Button(
            onClick = vm::commit,
            enabled = list.isNotEmpty() && !state.busy,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(if (list.isEmpty()) "Нечего загружать" else "Загрузить ${operations(list.size)}")
            }
        }
    }

    editing?.let { item ->
        CategoryDialog(
            options = vm.categoriesFor(item.row.isIncome),
            selected = item.category,
            onDismiss = { editing = null },
            onPick = { vm.setCategory(item, it); editing = null },
        )
    }
}

@Composable
private fun ImportRow(item: ImportItem, currency: String, included: Boolean, modifier: Modifier = Modifier, onCategory: () -> Unit) {
    val r = item.row
    val fin = FinTheme.colors
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val alpha = if (included) 1f else 0.5f
    val note = when (item.status) {
        ImportStatus.NEW -> null
        ImportStatus.ALREADY_IMPORTED -> "Уже загружено"
        ImportStatus.SIMILAR -> "Похоже на внесённую вручную"
        ImportStatus.NO_RATE -> "Нет курса ${r.currency}"
    }
    AppCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val c = item.category
            IconBadge(CategoryIcons.get(c?.iconKey), (c?.let { Color(it.color) } ?: fin.neutral).copy(alpha = alpha), size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.description.ifBlank { "Без описания" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                Text(
                    r.dateTime.format(ROW_DATE) + " · " + (c?.name ?: "Без категории"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (included) MaterialTheme.colorScheme.primary.readableOn(surface) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heightIn(min = 24.dp)
                        .clickable(enabled = included, onClickLabel = "Изменить категорию", onClick = onCategory),
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                FitText(
                    (if (r.isIncome) "+" else "−") + Money.format(item.amountMinor, currency),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = (if (r.isIncome) fin.income.readableOn(surface) else MaterialTheme.colorScheme.onSurface).copy(alpha = alpha),
                )
                if (item.originalMinor != null && item.originalCurrency != null) {
                    Text(
                        Money.format(item.originalMinor, item.originalCurrency),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryDialog(options: List<Category>, selected: Category?, onDismiss: () -> Unit, onPick: (Category) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Категория") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .bounceClick(pressedScale = 0.98f) { onPick(c) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(CategoryIcons.get(c.iconKey), Color(c.color), size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (c.parentId != null) "   " else "") + c.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (c.id == selected?.id) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun DoneStep(state: ImportUiState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(Icons.Rounded.CheckCircle, FinTheme.colors.statusGood, size = 72.dp, selected = true)
        Spacer(Modifier.height(18.dp))
        Text("Загружено: ${operations(state.importedCount)}", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Операции уже в истории и статистике. Если загрузить этот файл ещё раз, дублей не будет.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) { Text("Готово") }
    }
}
