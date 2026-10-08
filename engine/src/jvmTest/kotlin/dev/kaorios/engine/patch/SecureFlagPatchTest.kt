package dev.kaorios.engine.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Toolbox-docs/V2.0.3+/Disable_Secure_Flag.md` against the four site layouts this ROM actually
 * ships.
 *
 * The fixtures are abridged copies of the disassembled production classes: the register counts,
 * the `.param` debug lines, the blank-line style and the register numbers are all real, because
 * each of those is a separate way for a patch to assemble cleanly and still read the wrong
 * register at boot.
 */
class SecureFlagPatchTest {

    private val cache = """
        .class public Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;
        .super Ljava/lang/Object;

        .method public isScreenCaptureAllowed(I)Z
            .registers 5
            .param p1, "userHandle"    # I

            .line 82
            iget-object v0, p0, Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;->mLock:Ljava/lang/Object;

            monitor-enter v0

            .line 83
            invoke-static {p1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

            move-result-object v1

            if-nez v1, :cond_0

            const/4 v1, 0x1

            goto :goto_0

            :cond_0
            const/4 v1, 0x0

            :goto_0
            monitor-exit v0

            return v1
        .end method
    """.trimIndent() + "\n"

    private val windowState = """
        .class public Lcom/android/server/wm/WindowState;
        .super Ljava/lang/Object;

        .method isSecureLocked()Z
            .registers 6

            .line 2351
            iget-object v0, p0, Lcom/android/server/wm/WindowState;->mWmService:Lcom/android/server/wm/WindowManagerService;

            invoke-virtual {v0}, Lcom/android/server/wm/WindowManagerService;->getDisableSecureWindows()Z

            move-result v0

            return v0
        .end method

        .method setSecureLocked(Z)V
            .registers 10
            .param p1, "isSecure"    # Z

            .line 7567
            sget-object v0, Lcom/android/internal/protolog/ProtoLogImpl_2069928307${'$'}Cache;->WM_SHOW_TRANSACTIONS_enabled:[Z

            const/4 v1, 0x2

            aget-boolean v0, v0, v1

            if-eqz v0, :cond_0

            move v0, p1

            :cond_0
            iget-object v0, p0, Lcom/android/server/wm/WindowState;->mSurfaceControl:Landroid/view/SurfaceControl;

            if-nez v0, :cond_1

            return-void

            :cond_1
            invoke-virtual {p0}, Lcom/android/server/wm/WindowState;->getPendingTransaction()Landroid/view/SurfaceControl${'$'}Transaction;

            move-result-object v0

            invoke-virtual {v0, v1, p1}, Landroid/view/SurfaceControl${'$'}Transaction;->setSecure(Landroid/view/SurfaceControl;Z)Landroid/view/SurfaceControl${'$'}Transaction;

            return-void
        .end method
    """.trimIndent() + "\n"

    private val captureDisplay = """
        .class public Lcom/android/server/wm/WindowManagerService;
        .super Ljava/lang/Object;

        .method public captureDisplay(ILandroid/window/ScreenCaptureInternal${'$'}CaptureArgs;Landroid/window/ScreenCaptureInternal${'$'}ScreenCaptureListener;)V
            .registers 11
            .param p1, "displayId"    # I

            .line 12840
            invoke-static {}, Landroid/os/Binder;->getCallingUid()I

            move-result v0

            .line 12846
            const-string v4, "captureDisplay uid = "

            invoke-static {v4, v3}, Landroid/util/Slog;->d(Ljava/lang/String;Ljava/lang/String;)I

            .line 12849
            const-string v3, "android.permission.READ_FRAME_BUFFER"

            const-string v5, "captureDisplay()"

            invoke-virtual {p0, v3, v5}, Lcom/android/server/wm/WindowManagerService;->checkCallingPermission(Ljava/lang/String;Ljava/lang/String;)Z

            move-result v3

            if-eqz v3, :cond_5

            .line 12854
            const/4 v3, 0x0

            invoke-static {}, Lcom/android/server/wm/WindowManagerServiceStub;->get()Lcom/android/server/wm/WindowManagerServiceStub;

            move-result-object v5

            iget-object v6, p0, Lcom/android/server/wm/WindowManagerService;->mRoot:Lcom/android/server/wm/RootWindowContainer;

            invoke-interface {v5, v6, p1}, Lcom/android/server/wm/WindowManagerServiceStub;->notAllowCaptureDisplay(Lcom/android/server/wm/RootWindowContainer;I)Z

            move-result v5

            if-eqz v5, :cond_2

            .line 12856
            const-string v5, " Secure Window can\'t  captureDisplay return !"

            invoke-static {v4, v5}, Landroid/util/Slog;->d(Ljava/lang/String;Ljava/lang/String;)I

            .line 12861
            :cond_2
            return-void

            :cond_5
            return-void
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `site 1 forces isScreenCaptureAllowed to true above the param lines`() {
        val patched = SecureFlagPatch.patchDevicePolicyCache(cache)
        assertEquals(PatchStatus.PATCHED, patched.status)
        SecureFlagPatch.verifyDevicePolicyCache(patched.content)

        assertTrue(patched.content.contains(".registers 6"), "register directive was not grown")
        assertTrue(patched.content.contains(".param p1, \"userHandle\""), "param debug line was lost")
        assertTrue(
            Regex(
                "\\.registers 6\\s+invoke-static\\s*\\{\\},\\s*" +
                    Regex.escape(SecureFlagPatch.HOOK_SIGNATURE) +
                    "\\s+move-result\\s+v3\\s+if-eqz\\s+v3,\\s*:cond_kaorios\\s+" +
                    "const/4\\s+v3,\\s*0x1\\s+return\\s+v3\\s+:cond_kaorios"
            ).containsMatchIn(patched.content),
            "site 1 block is not the guide's:\n${patched.content}",
        )
    }

    @Test
    fun `site 2 patches both WindowState methods in one pass`() {
        val patched = SecureFlagPatch.patchWindowState(windowState)
        assertEquals(PatchStatus.PATCHED, patched.status)
        SecureFlagPatch.verifyWindowState(patched.content)

        assertTrue(patched.content.contains(".registers 7"), "isSecureLocked was not grown")
        assertTrue(patched.content.contains(".registers 11"), "setSecureLocked was not grown")
        assertTrue(
            patched.content.contains("const/4 v5, 0x0\n    return v5"),
            "isSecureLocked does not force false",
        )
        assertTrue(patched.content.contains("return-void\n    :cond_kaorios"), "setSecureLocked does not no-op")
        assertEquals(2, Regex("isSecureFlag").findAll(patched.content).count())
    }

    @Test
    fun `site 3 forces the notAllowCaptureDisplay verdict and keeps the stock branch`() {
        val patched = SecureFlagPatch.patchCaptureDisplay(captureDisplay)
        assertEquals(PatchStatus.PATCHED, patched.status)
        SecureFlagPatch.verifyCaptureDisplay(patched.content)

        assertTrue(patched.content.contains(".registers 12"), "captureDisplay was not grown")
        // v5 is the `move-result` that follows `notAllowCaptureDisplay`; v7 is the freed slot.
        assertTrue(
            patched.content.contains("move-result v5\n    invoke-static {}, ${SecureFlagPatch.HOOK_SIGNATURE}"),
            "site 3 is not injected directly below the verdict",
        )
        assertTrue(
            patched.content.contains("const/4 v5, 0x0\n    :cond_kaorios"),
            "site 3 did not force the verdict register",
        )
        assertTrue(
            patched.content.contains(":cond_kaorios\n\n    if-eqz v5, :cond_2"),
            "the stock branch the block exists to neutralise is gone",
        )
        assertFalse(patched.content.contains("const/4 v7"), "the scratch register was written to")
    }

    @Test
    fun `site 3 is skipped, not rejected, when the ROM has no blocking code`() {
        val withoutAnchor = captureDisplay.replace(
            Regex("invoke-interface[^\\n]*notAllowCaptureDisplay[^\\n]*\\n\\s*move-result v5\\n"),
            "invoke-static {}, Landroid/os/Binder;->getCallingUid()I\n\n    move-result v5\n",
        )
        assertFalse(withoutAnchor.contains("notAllowCaptureDisplay"))

        val outcome = SecureFlagPatch.patchCaptureDisplay(withoutAnchor)
        assertEquals(PatchStatus.NOT_TARGET, outcome.status)
        assertEquals(withoutAnchor, outcome.content, "a skipped site must leave the file byte-identical")
    }

    @Test
    fun `a second pass over an already patched file changes nothing`() {
        val once = SecureFlagPatch.patchDevicePolicyCache(cache)
        val twice = SecureFlagPatch.patchDevicePolicyCache(once.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, twice.status)
        assertEquals(once.content, twice.content)

        val windows = SecureFlagPatch.patchWindowState(windowState)
        assertEquals(PatchStatus.ALREADY_PATCHED, SecureFlagPatch.patchWindowState(windows.content).status)

        val display = SecureFlagPatch.patchCaptureDisplay(captureDisplay)
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            SecureFlagPatch.patchCaptureDisplay(display.content).status,
        )
    }

    @Test
    fun `the engine exposes the three sites as FULL-mode targets`() {
        assertEquals(
            setOf(
                "DevicePolicyCacheImpl.smali",
                "WindowState.smali",
                "WindowManagerService.smali",
            ),
            PatchEngine.DSV_TARGETS.keys,
        )
        assertTrue(PatchEngine.DSV_TARGETS.keys.all { it in PatchEngine.disassemblyFiles(PatchMode.FULL) })
        assertFalse("WindowStateAnimator.smali" in PatchEngine.DSV_TARGETS)
    }
}
