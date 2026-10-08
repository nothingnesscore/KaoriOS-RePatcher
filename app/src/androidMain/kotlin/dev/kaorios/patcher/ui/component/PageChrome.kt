package dev.kaorios.patcher.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal page gutter. Every page uses this so cards line up across tabs. */
val PageGutter: Dp = 16.dp

/** Extra room so the last card clears the floating navigation bar. */
val BottomBarClearance: Dp = 96.dp

/**
 * Standard scrolling page body: cards with a trailing clearance gap.
 *
 * The title lives in the shell's `TopAppBar`, not here, so the HOS large-title fold drives one
 * bar for every tab. [scrollState] is hoisted by the shell so the edge-blur strips and the title
 * bar read the same scroll position; the default keeps the page usable in isolation.
 */
@Composable
fun PageColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable () -> Unit,
) {
    val direction = LocalLayoutDirection.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + BottomBarClearance,
                start = PageGutter + contentPadding.calculateStartPadding(direction),
                end = PageGutter + contentPadding.calculateEndPadding(direction),
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
