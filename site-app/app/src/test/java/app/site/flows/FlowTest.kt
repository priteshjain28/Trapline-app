package app.site.flows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.site.AppContainer
import app.site.LocalContainer
import app.site.design.SiteAppTheme
import app.site.ui.SiteNavHost
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Base for flow tests: the real app (navigation, screens, demo data layer) on a simulated phone the size of the
 * reference screenshots, on a fixed day (Tue 13 Oct 2026, 09:30 IST). Any exception fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w448dp-h997dp-xxhdpi")
abstract class FlowTest {
    @get:Rule val compose = createComposeRule()

    val clock: Clock = Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"), ZoneId.of("Asia/Kolkata"))
    lateinit var container: AppContainer

    fun launch(signedIn: Boolean = true) {
        container = AppContainer(ApplicationProvider.getApplicationContext(), clock, latencyMs = 0, forceDemo = true)
        if (signedIn) runBlocking {
            container.session.verifyOtp("9876543210", "123456").getOrThrow()
            container.session.completeProfile("Anita Rao", null).getOrThrow()
        }
        compose.setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                SiteAppTheme(dark = false) { SiteNavHost() }
            }
        }
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- finders by what the user sees and hears

    fun text(s: String): SemanticsNodeInteraction = compose.onNode(hasText(s))
    fun field(label: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(label)
    fun named(desc: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(desc)
    fun tab(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    fun button(label: String): SemanticsNodeInteraction =
        compose.onNode((hasText(label) or hasContentDescription(label)) and hasClickAction())
    /** A filter chip or checkbox row, by its label. */
    fun chip(label: String): SemanticsNodeInteraction =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    /** Something tappable known only by what TalkBack says it does ("Your profile"). */
    fun action(label: String): SemanticsNodeInteraction =
        compose.onNode(SemanticsMatcher("click label '$label'") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label })

    fun type(label: String, value: String) { field(label).performTextInput(value); compose.waitForIdle() }
    fun tap(n: SemanticsNodeInteraction) { n.performClick(); compose.waitForIdle() }

    fun shows(s: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(s, substring = substring).fetchSemanticsNodes().isNotEmpty()

    fun showsDesc(s: String): Boolean = compose.onAllNodesWithContentDescription(s).fetchSemanticsNodes().isNotEmpty()

    fun waitFor(s: String, substring: Boolean = false, ms: Long = 5_000) =
        compose.waitUntil(ms) { shows(s, substring) }

    /** Scrolls the first scrollable container until a node with this text is composed and visible. */
    fun scrollTo(s: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s))
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- accessibility audit (the axe stand-in)

    /**
     * Every tappable element must have an accessible name (text, content description or click label)
     * and a touch target of at least 48x48dp. Returns the problems so tests can assert on them.
     */
    fun accessibilityProblems(): List<String> {
        val min = with(compose.density) { 48.dp.toPx() } - 1f
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().flatMap { n ->
            val c = n.config
            val name = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
                ?: c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }
                ?: c.getOrNull(SemanticsActions.OnClick)?.label
            buildList {
                if (name.isNullOrBlank()) add("unnamed tappable element at ${n.boundsInRoot}")
                if (n.size.width < min || n.size.height < min) {
                    val w = with(compose.density) { n.size.width.toDp() }
                    val h = with(compose.density) { n.size.height.toDp() }
                    add("'$name' touch target ${w.value.toInt()}x${h.value.toInt()}dp")
                }
            }
        }
    }

    fun assertAccessible(screen: String) {
        val problems = accessibilityProblems()
        assertTrue("$screen accessibility: $problems", problems.isEmpty())
    }

    /** Evidence for the manual pass: replica/test-screens/<name>.png (CI copies them). */
    fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/test-screens/$name.png")
    }
}
