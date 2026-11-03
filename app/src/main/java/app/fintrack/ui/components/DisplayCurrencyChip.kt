package app.fintrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Чип «BYN ▾»: в какой валюте показывать итог. Меню — валюты для выбора
 * и (необязательно) переход к курсам.
 */
@Composable
fun DisplayCurrencyChip(
    currency: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    contentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    onOpenRates: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(50))
                .background(containerColor)
                .bounceClick(pressedScale = 0.94f, role = Role.DropdownList, onClickLabel = "Выбрать валюту итога") { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(currency, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = contentColor)
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = contentColor, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Показывать итог в валюте",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            options.forEach { c ->
                DropdownMenuItem(
                    text = { Text(currencyTitle(c)) },
                    trailingIcon = { if (c == currency) Icon(Icons.Rounded.Check, null) },
                    onClick = { open = false; onSelect(c) },
                )
            }
            if (onOpenRates != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Курсы валют") },
                    leadingIcon = { Icon(Icons.Rounded.CurrencyExchange, null) },
                    onClick = { open = false; onOpenRates() },
                )
            }
        }
    }
}
