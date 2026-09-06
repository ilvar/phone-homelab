package dev.phoneport

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneport.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainFlowsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun connectionRequiresKeyAndPassesEnteredValues() {
        var connected = false
        compose.setContent { MaterialTheme {
            SettingsScreen(UiState(), { url, trust, key, _, _, apiMode ->
                assertEquals("http://127.0.0.1:9000", url); assertFalse(trust); assertEquals("test-key", key); assertTrue(apiMode); connected = true
            }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("API key").performScrollTo().performClick()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onAllNodesWithText("API key").filter(hasSetTextAction()).onFirst().performTextInput("test-key")
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(connected) }
    }
    @Test fun connectionDefaultsToTheStoredUsernameAndPassword() {
        var seen: List<Any>? = null
        val state = UiState(savedUsername = "admin", savedPassword = "generatedPassword12")
        compose.setContent { MaterialTheme {
            SettingsScreen(state, { _, _, _, user, pass, apiMode -> seen = listOf(user, pass, apiMode) },
                {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        // Prefilled, so Connect is usable without typing anything.
        compose.onNodeWithText("admin").assertIsDisplayed()
        compose.onNodeWithText("Connect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf<Any>("admin", "generatedPassword12", false), seen) }
    }
    @Test fun runPortainerAlwaysReachesTheModelSoRefusalsCanBeReported() {
        var started = false
        val state = UiState(vm = VmStage.STOPPED, vmMessage = "No local VM has been created yet.")
        compose.setContent { MaterialTheme {
            SettingsScreen(state, { _, _, _, _, _, _ -> }, {}, { _, _ -> }, {}, { started = true }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("No local VM has been created yet.").assertIsDisplayed()
        compose.onNodeWithText("Create and start the VM").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(started) }
    }
    @Test fun unusableTermuxExplainsItselfAndOffersNoStartButton() {
        val message = "This Termux build has no RUN_COMMAND service, so PhonePort cannot start a VM through it."
        compose.setContent { MaterialTheme {
            SettingsScreen(UiState(vm = VmStage.TERMUX_UNAVAILABLE, vmMessage = message),
                { _, _, _, _, _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onAllNodesWithText("Create and start the VM").assertCountEquals(0)
    }
    @Test fun runPortainerIsDisabledWhileTheVmIsAlreadyUp() {
        compose.setContent { MaterialTheme {
            SettingsScreen(UiState(vm = VmStage.RUNNING, vmMessage = "Portainer is answering on http://127.0.0.1:9000"),
                { _, _, _, _, _, _ -> }, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Portainer is running").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Stop the VM").assertIsDisplayed()
    }
    @Test fun installedTilesOfferActionsAndAddApp() {
        var added = false; var action = ""
        val app = InstalledApp("c1", "My Nginx", "nginx:latest", "running", "Up 2 hours")
        compose.setContent { MaterialTheme { HomeScreen(UiState(installed = listOf(app)), { added = true }, {}, { _, value -> action = value }, {}, {}) } }
        compose.onNodeWithText("My Nginx").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop").performClick()
        compose.runOnIdle { assertEquals("stop", action) }
        compose.onNodeWithText("+ Add app").performClick()
        compose.runOnIdle { assertTrue(added) }
    }
    @Test fun removeRequiresConfirmation() {
        var removed = false
        val app = InstalledApp("c1", "Test", "alpine", "stopped", "Exited")
        compose.setContent { MaterialTheme { HomeScreen(UiState(installed = listOf(app)), {}, {}, { _, action -> removed = action == "remove" }, {}, {}) } }
        compose.onNodeWithText("Test").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertFalse(removed) }
        compose.onNodeWithText("Remove Test?").assertIsDisplayed()
        compose.onNodeWithText("Remove", substring = false).performClick()
        compose.runOnIdle { assertTrue(removed) }
    }
    @Test fun requiredEnvironmentGatesDeploy() {
        var deployed: Map<String, String>? = null
        val template = Template("Database", 1, "postgres", env = listOf(EnvField("PASSWORD", "Database password")))
        compose.setContent { MaterialTheme { DetailScreen(template, Architecture.ARM64, false) { _, env -> deployed = env } } }
        compose.onNodeWithText("Deploy").assertIsNotEnabled()
        compose.onNodeWithText("Database password *").performScrollTo().performTextInput("local-secret")
        compose.onNodeWithText("Deploy").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("local-secret", deployed?.get("PASSWORD")) }
    }
    @Test fun catalogTileOpensDetailInOneTap() {
        var selected: Template? = null
        val template = Template("Nginx", 1, "nginx", categories = listOf("Web"))
        compose.setContent { MaterialTheme { CatalogScreen(UiState(catalog = listOf(template), filtered = listOf(template)), {}, { _, _, _, _ -> }, { selected = it }, {}, { null }) } }
        compose.onNodeWithText("Nginx").performClick()
        compose.runOnIdle { assertEquals(template, selected) }
    }
}
