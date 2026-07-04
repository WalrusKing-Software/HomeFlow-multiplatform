package org.homeflow.app.shared.ui.components.kmp.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// SideNavRail
//
// A Material 3 NavigationRail for desktop and tablet layouts.
// Fully hoisted — the caller owns the selected index.
//
// Each NavRailItem carries a required icon and label, and an optional badge
// count that renders as a numeric badge when > 0.
//
// Usage:
//   data class NavItem(
//       val label: String,
//       val icon: ImageVector,
//       val badgeCount: Int = 0,
//   )
//
//   val items = listOf(
//       NavItem("Dashboard", Icons.Default.Home),
//       NavItem("Patients", Icons.Default.People, badgeCount = 3),
//       NavItem("Reports", Icons.Default.BarChart),
//   )
//   var selected by remember { mutableStateOf(0) }
//
//   SideNavRail(
//       items = items,
//       selectedIndex = selected,
//       onItemSelected = { selected = it },
//       icon = { it.icon },
//       label = { it.label },
//       badgeCount = { it.badgeCount },
//   )
// ---------------------------------------------------------------------------

@Composable
fun <T> SideNavRail(
    items: List<T>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    icon: (T) -> ImageVector,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    badgeCount: ((T) -> Int)? = null,
    header: @Composable (() -> Unit)? = null,
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        header = if (header != null) ({
            Spacer(Modifier.height(8.dp))
            header()
            Spacer(Modifier.height(8.dp))
        }) else null,
    ) {
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { index, item ->
            val count = badgeCount?.invoke(item) ?: 0
            NavigationRailItem(
                selected = index == selectedIndex,
                onClick = { onItemSelected(index) },
                icon = {
                    if (count > 0) {
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                androidx.compose.material3.Badge {
                                    Text(
                                        text = if (count > 99) "99+" else count.toString(),
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = icon(item),
                                contentDescription = label(item),
                            )
                        }
                    } else {
                        Icon(
                            imageVector = icon(item),
                            contentDescription = label(item),
                        )
                    }
                },
                label = { Text(label(item)) },
                alwaysShowLabel = true,
            )
        }
    }
}
