package io.nisfeb.talon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ui.screens.MenuBadgeDot

/**
 * How a row of tabs draws at the width it has, roomiest first. Each
 * label stays on one line in every form; as the row narrows the padding
 * tightens, then the label shrinks, then the row becomes one dropdown.
 */
enum class TabForm(
    /** Space either side of a label. Material's own tab pads 16dp. */
    val padding: Dp,
) {
    Full(16.dp),
    Tight(6.dp),
    Small(6.dp),
    Dropdown(0.dp),
}

/**
 * The roomiest form in which every tab shows its whole label on one
 * line. A fixed row shares its width equally, so the widest label
 * decides; a scrollable row needs room for two of its widest tabs, so
 * there is always one beside the current to scroll to.
 * [widestLabel] is the widest label in a form, any dot included.
 */
fun tabForm(rowWidth: Dp, tabCount: Int, scrollable: Boolean, widestLabel: (TabForm) -> Dp): TabForm {
    val share = rowWidth / (if (scrollable) 2 else tabCount.coerceAtLeast(1))
    // ponytail: 1dp of slack for Material rounding each tab to whole pixels.
    return TabForm.entries.first { it == TabForm.Dropdown || widestLabel(it) + it.padding * 2 + 1.dp <= share }
}

private fun TabForm.style(t: Typography): TextStyle =
    if (this == TabForm.Small) t.labelMedium else t.titleSmall

/** The dot after a label: [MenuBadgeDot] and the gap before it. */
private val DOT_WIDTH = 14.dp

/**
 * The one tab row every screen uses: full Material tabs where the labels
 * fit, denser ones where only those fit, and a dropdown of the tabs where
 * not even those do. The fit is measured, in the font and text size in
 * use, not guessed. A label is never wrapped onto a second line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveTabRow(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Tabs whose label carries a dot (someone on a party line). */
    dots: Set<Int> = emptySet(),
    /** Many tabs that scroll rather than share the width (Settings). */
    scrollable: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val widest = remember(labels, dots, typography, density) {
        TabForm.entries.associateWith { form ->
            labels.indices.maxOfOrNull { i ->
                val px = measurer.measure(labels[i], form.style(typography), maxLines = 1).size.width
                with(density) { px.toDp() } + if (i in dots) DOT_WIDTH else 0.dp
            } ?: 0.dp
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val form = tabForm(maxWidth, labels.size, scrollable) { widest.getValue(it) }
        val at = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        if (form == TabForm.Dropdown) {
            TabDropdown(labels, at, onSelect, dots)
            return@BoxWithConstraints
        }
        val tabs: @Composable () -> Unit = {
            labels.forEachIndexed { i, label ->
                if (form == TabForm.Full) {
                    Tab(
                        selected = i == at,
                        onClick = { onSelect(i) },
                        text = { TabLabel(label, i in dots, form.style(typography)) },
                    )
                } else {
                    Tab(selected = i == at, onClick = { onSelect(i) }) {
                        TabLabel(
                            label, i in dots, form.style(typography),
                            Modifier.heightIn(min = 40.dp).padding(horizontal = form.padding),
                        )
                    }
                }
            }
        }
        // Secondary: the full-tab underline the screens' TabRows had.
        if (scrollable) SecondaryScrollableTabRow(selectedTabIndex = at, edgePadding = 12.dp, tabs = tabs)
        else SecondaryTabRow(selectedTabIndex = at, tabs = tabs)
    }
}

@Composable
private fun TabLabel(label: String, dot: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (dot) MenuBadgeDot(Modifier.padding(start = 6.dp))
    }
}

/** The narrowest form: the current tab, which opens a menu of all of them. */
@Composable
private fun TabDropdown(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, dots: Set<Int>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList) { open = true }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A dot on any tab shows here, or it would hide in the menu.
                Box(Modifier.weight(1f, fill = false)) {
                    TabLabel(labels.getOrElse(selected) { "" }, dots.isNotEmpty(), MaterialTheme.typography.titleSmall)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                labels.forEachIndexed { i, label ->
                    DropdownMenuItem(
                        text = { TabLabel(label, i in dots, MaterialTheme.typography.bodyLarge) },
                        leadingIcon = {
                            if (i == selected) Icon(Icons.Filled.Check, contentDescription = "Current")
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
