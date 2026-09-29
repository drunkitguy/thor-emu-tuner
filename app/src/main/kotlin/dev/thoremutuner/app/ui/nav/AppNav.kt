package dev.thoremutuner.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.thoremutuner.app.AppContainer
import dev.thoremutuner.app.ui.apply.ApplyScreen
import dev.thoremutuner.app.ui.game.GameScreen
import dev.thoremutuner.app.ui.history.CompareScreen
import dev.thoremutuner.app.ui.history.HistoryScreen
import dev.thoremutuner.app.ui.library.LibraryScreen
import dev.thoremutuner.app.ui.onboarding.OnboardingScreen
import dev.thoremutuner.app.ui.preset.BaselineScreen
import dev.thoremutuner.app.ui.settings.AboutScreen
import dev.thoremutuner.app.ui.settings.SettingsScreen
import dev.thoremutuner.app.ui.test.ResultScreen
import dev.thoremutuner.app.ui.test.TestScreen
import dev.thoremutuner.app.ui.tweak.TweakScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val RESULT = "result"
    fun game(key: String) = "game/$key"
    fun baseline(key: String, emu: String) = "baseline/$key/$emu"
    fun tweak(key: String, emu: String) = "tweak/$key/$emu"
    fun apply(key: String, emu: String) = "apply/$key/$emu"
    fun test(key: String, emu: String) = "test/$key/$emu"
    fun history(key: String) = "history/$key"
    fun compare(key: String, a: String, b: String) = "compare/$key/$a/$b"
}

private fun NavBackStackEntry.arg(name: String): String = arguments?.getString(name).orEmpty()

private val keyArg = navArgument("key") { type = NavType.StringType }
private val emuArg = navArgument("emu") { type = NavType.StringType }

@Composable
fun AppNav(container: AppContainer) {
    val settings by container.settings.flow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.settings.get() }
    val loaded = settings
    if (loaded == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val nav = rememberNavController()
    // Chosen once: later settings changes must not reset the graph.
    val start = remember { if (loaded.onboardingDone) Routes.LIBRARY else Routes.ONBOARDING }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(container, onDone = {
                    nav.navigate(Routes.LIBRARY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    container,
                    openGame = { nav.navigate(Routes.game(it)) },
                    openSettings = { nav.navigate(Routes.SETTINGS) },
                    openResult = { nav.navigate(Routes.RESULT) },
                )
            }
            composable("game/{key}", arguments = listOf(keyArg)) { e ->
                val key = e.arg("key")
                GameScreen(
                    container, key,
                    onBack = { nav.popBackStack() },
                    openBaseline = { emu -> nav.navigate(Routes.baseline(key, emu)) },
                    openTweak = { emu -> nav.navigate(Routes.tweak(key, emu)) },
                    openApply = { emu -> nav.navigate(Routes.apply(key, emu)) },
                    openTest = { emu -> nav.navigate(Routes.test(key, emu)) },
                    openHistory = { nav.navigate(Routes.history(key)) },
                )
            }
            composable("baseline/{key}/{emu}", arguments = listOf(keyArg, emuArg)) { e ->
                BaselineScreen(container, e.arg("key"), e.arg("emu"), onBack = { nav.popBackStack() })
            }
            composable("tweak/{key}/{emu}", arguments = listOf(keyArg, emuArg)) { e ->
                TweakScreen(container, e.arg("key"), e.arg("emu"), onBack = { nav.popBackStack() })
            }
            composable("apply/{key}/{emu}", arguments = listOf(keyArg, emuArg)) { e ->
                ApplyScreen(container, e.arg("key"), e.arg("emu"), onBack = { nav.popBackStack() })
            }
            composable("test/{key}/{emu}", arguments = listOf(keyArg, emuArg)) { e ->
                TestScreen(
                    container, e.arg("key"), e.arg("emu"),
                    onBack = { nav.popBackStack() },
                    openResult = { nav.navigate(Routes.RESULT) { popUpTo("test/{key}/{emu}") { inclusive = true } } },
                )
            }
            composable(Routes.RESULT) {
                ResultScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    openHistory = { key -> nav.navigate(Routes.history(key)) { popUpTo(Routes.RESULT) { inclusive = true } } },
                )
            }
            composable("history/{key}", arguments = listOf(keyArg)) { e ->
                val key = e.arg("key")
                HistoryScreen(container, key, onBack = { nav.popBackStack() }, openCompare = { a, b -> nav.navigate(Routes.compare(key, a, b)) })
            }
            composable(
                "compare/{key}/{a}/{b}",
                arguments = listOf(keyArg, navArgument("a") { type = NavType.StringType }, navArgument("b") { type = NavType.StringType }),
            ) { e ->
                CompareScreen(container, e.arg("key"), e.arg("a"), e.arg("b"), onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container,
                    onBack = { nav.popBackStack() },
                    openAbout = { nav.navigate(Routes.ABOUT) },
                    rerunOnboarding = { nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                )
            }
            composable(Routes.ABOUT) { AboutScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
