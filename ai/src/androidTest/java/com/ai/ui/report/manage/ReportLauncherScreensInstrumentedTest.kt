package com.ai.ui.report.manage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportLauncherScreensInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun createOverviewRendersMetaOptionsAndInvokesEnabledMetaAction() {
        val metaClicks = mutableIntStateOf(0)
        val compareClicks = mutableIntStateOf(0)

        rule.setContent {
            MaterialTheme {
                ReportCreateOverviewScreen(
                    metaEnabled = true,
                    compareEnabled = false,
                    onMeta = { metaClicks.intValue++ },
                    onCompare = { compareClicks.intValue++ },
                    onBack = {}
                )
            }
        }

        rule.onNodeWithText("Run a meta prompt over the answers").assertIsDisplayed()
        rule.onNodeWithText("Compare, critique or synthesize the answers").assertIsDisplayed()
        rule.onNodeWithText("Compare with meta").assertIsDisplayed()
        rule.onNodeWithText("Score each answer's similarity to a meta result").assertIsDisplayed()

        rule.onNodeWithText("Compare, critique or synthesize the answers").performClick()

        assertThat(metaClicks.intValue).isEqualTo(1)
        assertThat(compareClicks.intValue).isEqualTo(0)
    }

    @Test
    fun tournamentOverviewRendersHeadToHeadOptionsAndInvokesEnabledTournamentAction() {
        val tournamentClicks = mutableIntStateOf(0)
        val judgeClicks = mutableIntStateOf(0)

        rule.setContent {
            MaterialTheme {
                ReportTournamentOverviewScreen(
                    tournamentEnabled = true,
                    judgeJudgesEnabled = false,
                    onTournament = { tournamentClicks.intValue++ },
                    onJudgeJudges = { judgeClicks.intValue++ },
                    onBack = {}
                )
            }
        }

        rule.onNodeWithText("Head-to-head tools").assertIsDisplayed()
        rule.onNodeWithText("Head-to-head judge every pair of answers").assertIsDisplayed()
        rule.onNodeWithText("Judge the judges").assertIsDisplayed()
        rule.onNodeWithText("Score the judge models by how they judge 25 head-to-heads").assertIsDisplayed()

        rule.onNodeWithText("Head-to-head judge every pair of answers").performClick()

        assertThat(tournamentClicks.intValue).isEqualTo(1)
        assertThat(judgeClicks.intValue).isEqualTo(0)
    }
}
