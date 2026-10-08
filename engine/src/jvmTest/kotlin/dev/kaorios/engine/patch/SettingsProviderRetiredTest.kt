package dev.kaorios.engine.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Upstream `8c752fd` removed SettingsProvider patching entirely, mirroring
 * `script/test_settingsprovider_anchor.py`'s retired-provider coverage: a run whose only
 * input is the provider reports nothing and fails, the retired entry point raises the
 * upstream message, and no mode can reach the class at all.
 *
 * The stock APK stays byte-identical — the guide's "Save and check" section now says
 * "Keep the stock SettingsProvider. Fake Settings has been removed".
 */
class SettingsProviderRetiredTest {

    private val stockProvider = """
        .class public Lcom/android/providers/settings/SettingsProvider;
        .super Landroid/content/ContentProvider;

        .method public onCreate()Z
            .registers 2

            const/4 v0, 0x1
            return v0
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `no mode reaches the provider`() {
        for (mode in PatchMode.entries) {
            assertFalse(
                "SettingsProvider.smali" in PatchEngine.targets(mode).keys,
                "$mode still patches the provider",
            )
            assertFalse(
                "SettingsProvider.smali" in PatchEngine.disassemblyFiles(mode),
                "$mode still disassembles the provider",
            )
        }
    }

    /** The directory-level analogue of the reference's `process_files` over an SP-only tree. */
    @Test
    fun `a run whose only input is the provider reports nothing and fails`() {
        val report = PatchEngine.run(
            PatchMode.HOOKS,
            mapOf("SettingsProvider.smali" to stockProvider),
        )
        assertTrue(report.outcomes.isEmpty(), "the provider must not be a target: ${report.outcomes}")
        assertFalse(report.ok, "an empty report is not a successful run")
    }

    @Test
    fun `the retired entry point fails closed with the upstream message`() {
        val patch = assertFailsWith<IllegalStateException> {
            SettingsProviderPatch.patch(stockProvider)
        }
        assertContains(patch.message!!, "Fake Settings has been removed")
        assertContains(patch.message!!, "framework.jar and services.jar only")

        val verify = assertFailsWith<IllegalStateException> {
            SettingsProviderPatch.verify(stockProvider)
        }
        assertContains(verify.message!!, "Fake Settings has been removed")
    }

    /** Even a hand-built target map cannot push the provider through the shared entry point. */
    @Test
    fun `applyTargetPatch refuses the provider and leaves the text untouched`() {
        val targets = mapOf(
            "SettingsProvider.smali" to PatchTarget(
                "SettingsProvider.smali",
                SettingsProviderPatch::patch,
                SettingsProviderPatch::verify,
            ),
        )
        val outcome = PatchEngine.applyTargetPatch("SettingsProvider.smali", stockProvider, targets)
        assertEquals(PatchStatus.FAILED, outcome.status)
        assertEquals(stockProvider, outcome.content, "a rejection must be byte-identical")
        assertContains(outcome.error!!, "Fake Settings has been removed")
    }
}
