package org.homeflow.app.shared.ui.components.kmp.navigation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

// ---------------------------------------------------------------------------
// SegmentedTabBar
//
// A Material 3 SingleChoiceSegmentedButtonRow for in-screen tab switching.
// Fully hoisted — the caller owns the selected index.
//
// Accepts an optional icon per tab. Labels are always shown; icons appear
// above the label when provided (standard M3 segmented button behaviour).
//
// Usage:
//   data class Tab(val label: String, val icon: ImageVector? = null)
//
//   val tabs = listOf(Tab("Overview"), Tab("History"), Tab("Notes"))
//   var selectedTab by remember { mutableStateOf(0) }
//
//   SegmentedTabBar(
//       tabs = tabs,
//       selectedIndex = selectedTab,
//       onTabSelected = { selectedTab = it },
//       label = { it.label },
//   )
// ---------------------------------------------------------------------------

@Composable
fun <T> SegmentedTabBar(
    tabs: List<T>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, tab ->
            val tabIcon = icon?.invoke(tab)
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onTabSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = tabs.size,
                ),
                icon = {
                    if (tabIcon != null) {
                        SegmentedButtonDefaults.Icon(active = index == selectedIndex) {
                            androidx.compose.material3.Icon(
                                imageVector = tabIcon,
                                contentDescription = null,
                            )
                        }
                    } else {
                        SegmentedButtonDefaults.Icon(active = index == selectedIndex)
                    }
                },
            ) {
                Text(label(tab))
            }
        }
    }
}
