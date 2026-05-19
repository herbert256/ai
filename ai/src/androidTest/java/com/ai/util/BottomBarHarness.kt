package com.ai.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ai.ui.shared.BottomIconBar
import com.ai.ui.shared.LocalBottomIconState
import com.ai.ui.shared.LocalNavigateHome
import com.ai.ui.shared.LocalNavigateToHelp
import com.ai.ui.shared.LocalShowBackArrow
import com.ai.ui.shared.TitleBarIcons

/**
 * Test harness that renders a screen together with the global
 * [BottomIconBar] — the bar that actually paints the back arrow and
 * the Home / Help / action icons.
 *
 * In the running app a screen's [com.ai.ui.shared.TitleBar] only
 * *publishes* its icons into [LocalBottomIconState]; [BottomIconBar]
 * (mounted once by AppNavHost) renders them. A screen rendered in
 * isolation therefore has no visible back / home affordance, so
 * smoke tests that exercise those have to wire this up themselves.
 *
 * Affordance text nodes the wrapped content can target:
 *  - back arrow → `"←"`
 *  - Home → `"🏠"`
 *  - Help → `"❓"`
 */
@Composable
fun WithBottomBar(
    onNavigateHome: () -> Unit = {},
    onNavigateToHelp: (String?) -> Unit = {},
    content: @Composable () -> Unit
) {
    val iconState = remember { mutableStateOf<TitleBarIcons?>(null) }
    MaterialTheme {
        CompositionLocalProvider(
            LocalBottomIconState provides iconState,
            LocalNavigateHome provides onNavigateHome,
            LocalNavigateToHelp provides onNavigateToHelp,
            // Real app gates the ← arrow on this local (default false);
            // tests expect to be able to click ← regardless of the
            // user's preference, so force it on inside the harness.
            LocalShowBackArrow provides true
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) { content() }
                BottomIconBar(icons = iconState.value)
            }
        }
    }
}
