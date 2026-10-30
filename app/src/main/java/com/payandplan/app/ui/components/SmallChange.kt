package com.payandplan.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.payandplan.app.data.Payment
import com.payandplan.app.ui.theme.Ink
import com.payandplan.app.ui.theme.Tangerine
import com.payandplan.app.util.Format

/**
 * The day's small change, told apart from the rest of a day once and for all. Every list
 * that shows a day asks here, so a coffee is a coffee in the calendar, in the day and
 * anywhere else, instead of being folded in one place and spread out in another.
 */
fun List<Payment>.splitSmallChange(under: Long): Pair<List<Payment>, List<Payment>> {
    val small = filter { it.isSmallChange(under) }
    // one purchase on its own is not a lump; it reads better under its own name
    return if (small.size < 2) this to emptyList() else filterNot { it in small } to small
}

/** One line standing in for all of them, with what it adds up to. */
@Composable
fun SmallChangeRow(
    entries: List<Payment>,
    currency: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val total = entries.filter { !it.isSuspended }.sumOf { it.amountCents }
    val loose = entries.count { it.category.isBlank() }

    ComicCard(color = Tangerine, modifier = modifier.fillMaxWidth().clickable { onClick() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "🧾 SMALL CHANGE (${entries.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink
                )
                Text(
                    entries.take(3).joinToString(", ") { it.title } +
                        if (entries.size > 3) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (loose > 0) {
                    Text(
                        "$loose still without a category",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink
                    )
                }
            }
            Text(
                Format.money(total, currency),
                style = MaterialTheme.typography.titleMedium,
                color = Ink
            )
        }
    }
}
