package app.fintrack.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Переключатель «Учитывать в общем балансе». Нажимается вся строка (крупная цель касания),
 * сам Switch — только индикатор, чтобы TalkBack объявлял один элемент.
 */
@Composable
fun IncludeInTotalRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    what: String = "счёт",
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = checked,
                transitionSpec = { fadeIn(tween(Motion.MEDIUM_MS)).togetherWith(fadeOut(tween(Motion.SHORT_MS))) },
                label = "includeIcon",
            ) { on ->
                IconBadge(
                    if (on) Icons.Rounded.AccountBalanceWallet else Icons.Rounded.VisibilityOff,
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    size = 40.dp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Учитывать в общем балансе", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (checked) {
                        "Остаток входит в сумму на главной и в «Счетах»"
                    } else {
                        "Этот $what виден в списке и истории, но его остаток не прибавляется к общему балансу. Доходы и расходы учитываются как обычно"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
