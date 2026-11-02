@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.fintrack.ui.categories

import app.fintrack.ui.components.Motion
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.domain.model.Category
import app.fintrack.domain.model.CategoryKind
import app.fintrack.ui.components.AppCard
import app.fintrack.ui.components.CategoryIcons
import app.fintrack.ui.components.ColorPicker
import app.fintrack.ui.components.ConfirmDialog
import app.fintrack.ui.components.EmptyState
import app.fintrack.ui.components.IconBadge
import app.fintrack.ui.components.SectionTitle
import app.fintrack.ui.components.bounceClick
import app.fintrack.ui.components.popOnChange

@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onAddCategory: (kind: String, parentId: String?) -> Unit,
    vm: CategoriesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.messageFlow.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Категории") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = { onAddCategory(state.kind.name, null) }) { Icon(Icons.Rounded.Add, "Новая категория") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    listOf(CategoryKind.EXPENSE to "Расходы", CategoryKind.INCOME to "Доходы").forEachIndexed { i, (k, label) ->
                        SegmentedButton(
                            selected = state.kind == k,
                            onClick = { vm.setKind(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
            }
            if (state.active.isEmpty()) {
                item { EmptyState("Категорий нет") }
            }
            items(state.active, key = { it.category.id }) { node ->
                AppCard(
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 5.dp)
                        .fillMaxWidth(),
                ) {
                    Column {
                        CategoryRow(node.category, indent = false) { onOpenCategory(node.category.id) }
                        node.children.forEach { child ->
                            HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            CategoryRow(child, indent = true) { onOpenCategory(child.id) }
                        }
                        TextButton(
                            onClick = { onAddCategory(node.category.kind.name, node.category.id) },
                            modifier = Modifier.padding(start = 64.dp),
                        ) { Text("+ Подкатегория") }
                    }
                }
            }
            if (state.archived.isNotEmpty()) {
                item { SectionTitle("Архив") }
                items(state.archived, key = { "a:" + it.id }) { c ->
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(CategoryIcons.get(c.iconKey), Color(c.color).copy(alpha = 0.5f), size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.restore(c.id) }) { Text("Восстановить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(c: Category, indent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(pressedScale = 0.98f, onClick = onClick)
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(CategoryIcons.get(c.iconKey), Color(c.color), size = if (indent) 32.dp else 40.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            c.name,
            style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun CategoryEditScreen(
    onClose: () -> Unit,
    vm: CategoryEditViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                CategoryEditEvent.Closed -> onClose()
                is CategoryEditEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            !state.isNew -> "Категория"
                            state.parentId != null -> "Новая подкатегория"
                            state.kind == CategoryKind.INCOME -> "Новая категория дохода"
                            else -> "Новая категория расхода"
                        },
                    )
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
                actions = {
                    if (!state.isNew && !state.isSystem) {
                        IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Rounded.Delete, "Удалить") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    CategoryIcons.get(state.iconKey),
                    Color(state.color),
                    size = 60.dp,
                    selected = true,
                    modifier = Modifier.popOnChange(state.iconKey to state.color, peak = 1.12f),
                )
                Spacer(Modifier.width(16.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    label = { Text("Название") },
                    isError = state.nameError,
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
            }

            if (!state.hasChildren && !state.isSystem) {
                ParentDropdown(state.parents, state.parentId, vm::setParent)
            }

            Text("Иконка", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Группы иконок: открывается группа текущей иконки
            var group by remember(state.loading) { mutableIntStateOf(CategoryIcons.groupIndexOf(state.iconKey)) }
            var direction by remember { mutableIntStateOf(1) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(CategoryIcons.groups) { i, g ->
                    FilterChip(
                        selected = i == group,
                        onClick = {
                            direction = if (i >= group) 1 else -1
                            group = i
                        },
                        label = { Text(g.title) },
                        leadingIcon = if (g.icons.any { it.first == state.iconKey }) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                }
            }
            AnimatedContent(
                targetState = group,
                transitionSpec = {
                    (slideInHorizontally(tween(Motion.MEDIUM_MS)) { it / 4 * direction } + fadeIn(tween(Motion.MEDIUM_MS)))
                        .togetherWith(slideOutHorizontally(tween(Motion.SHORT_MS)) { -it / 4 * direction } + fadeOut(tween(Motion.SHORT_MS)))
                        .using(SizeTransform(clip = false))
                },
                label = "iconGroup",
            ) { g ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CategoryIcons.groups[g].icons.forEach { (key, icon) ->
                        val selected = key == state.iconKey
                        IconBadge(
                            icon = icon,
                            color = if (selected) Color(state.color) else MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 48.dp,
                            selected = selected,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .bounceClick(pressedScale = 0.85f, role = Role.RadioButton) { vm.setIcon(key) },
                        )
                    }
                }
            }

            Text("Цвет", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ColorPicker(state.color, vm::setColor)

            Button(
                onClick = vm::save,
                enabled = !state.saving && !state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (state.isNew) "Создать" else "Сохранить") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Удалить категорию?",
            text = "Если по категории есть операции, она будет перемещена в архив, а история сохранится." +
                if (state.hasChildren) " Подкатегории будут обработаны так же." else "",
            confirm = "Удалить",
            onConfirm = {
                confirmRemove = false
                vm.remove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun ParentDropdown(parents: List<Category>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = parents.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "Нет (основная категория)",
            onValueChange = {},
            readOnly = true,
            label = { Text("Родительская категория") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Нет (основная категория)") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            parents.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = { IconBadge(CategoryIcons.get(p.iconKey), Color(p.color), size = 28.dp) },
                    onClick = {
                        onSelect(p.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
