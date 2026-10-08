package dev.kaorios.engine.patch

import dev.kaorios.engine.smali.PatchVerificationException
import dev.kaorios.engine.smali.UnsupportedLayoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Toolbox-docs/V2.0.3+/CorePatch.md` §1 and §2 against the layouts this ROM ships.
 *
 * The fixtures are abridged copies of the disassembled production classes: register counts,
 * `.param` debug lines, label naming and the number of `const/4 vN, 0x0` instructions that happen
 * to sit in the file are all real, because each is a separate way for a patch to force the wrong
 * register — or for `ReconcilePackageUtils` to rewrite a constant outside `<clinit>`.
 */
class CorePatchPatchTest {

    private val packageParser = """
        .class public Landroid/content/pm/PackageParser;
        .super Ljava/lang/Object;

        .method public static blacklist parseBaseApk(Ljava/lang/String;I)Landroid/content/pm/pkg/parsing/ParsingPackage;
            .registers 10
            .param p0, "file"    # Ljava/lang/String;

            .line 1420
            if-eqz p1, :cond_0

            .line 1425
            invoke-static {v2, v0, v1}, Landroid/util/apk/ApkSignatureVerifier;->unsafeGetCertsWithoutVerification(Landroid/content/pm/parsing/result/ParseInput;Ljava/lang/String;I)Landroid/content/pm/parsing/result/ParseResult;

            move-result-object v3

            :cond_0
            return-object v3
        .end method

        .method public static blacklist checkSharedUserId(Landroid/content/pm/pkg/parsing/ParsingPackage;)V
            .registers 6

            invoke-interface {p0}, Landroid/content/pm/pkg/parsing/ParsingPackage;->isSharedUserId()Z

            move-result v0

            if-nez v0, :cond_1

            .line 1962
            new-instance v1, Ljava/lang/StringBuilder;

            invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

            const-string v3, "<manifest> specifies bad sharedUserId name \""

            return-void

            :cond_1
            return-void
        .end method
    """.trimIndent() + "\n"

    private val packageParserException = """
        .class public Landroid/content/pm/PackageParser${'$'}PackageParserException;
        .super Ljava/lang/Exception;

        .field public final greylist-max-o error:I

        .method public constructor greylist-max-o <init>(ILjava/lang/String;)V
            .registers 3
            .param p1, "error"    # I
            .param p2, "detailMessage"    # Ljava/lang/String;

            .line 8753
            invoke-direct {p0, p2}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

            .line 8754
            iput p1, p0, Landroid/content/pm/PackageParser${'$'}PackageParserException;->error:I

            return-void
        .end method

        .method public constructor greylist-max-o <init>(ILjava/lang/String;Ljava/lang/Throwable;)V
            .registers 4
            .param p1, "error"    # I
            .param p3, "cause"    # Ljava/lang/Throwable;

            .line 8771
            invoke-direct {p0, p2}, Ljava/lang/Exception;-><init>(Ljava/lang/String;)V

            .line 8772
            iput p1, p0, Landroid/content/pm/PackageParser${'$'}PackageParserException;->error:I

            return-void
        .end method
    """.trimIndent() + "\n"

    private val signingDetails = """
        .class public Landroid/content/pm/SigningDetails;
        .super Ljava/lang/Object;

        .method public checkCapability(Landroid/content/pm/SigningDetails;I)Z
            .registers 6

            .line 210
            const/4 v0, 0x0

            return v0
        .end method

        .method public checkCapability(Ljava/lang/String;I)Z
            .registers 6

            const/4 v0, 0x0

            return v0
        .end method

        .method public checkCapabilityRecover(Landroid/content/pm/SigningDetails;I)Z
            .registers 6

            const/4 v0, 0x0

            return v0
        .end method

        .method public hasAncestorOrSelf(Landroid/content/pm/SigningDetails;)Z
            .registers 5

            const/4 v0, 0x0

            return v0
        .end method

        .method public hasAncestorOrSelfWithDigest(Landroid/content/pm/SigningDetails;)[B
            .registers 5

            return-object v0
        .end method
    """.trimIndent() + "\n"

    private fun digestVerifier(name: String) = """
        .class public Landroid/util/apk/$name;
        .super Ljava/lang/Object;

        .method private static blacklist verifyChunkDigest([B[B)Z
            .registers 7

            .line 300
            invoke-static {v3, v4}, Ljava/security/MessageDigest;->isEqual([B[B)Z

            move-result v0

            if-eqz v0, :cond_5

            return v0

            :cond_5
            return v0
        .end method
    """.trimIndent() + "\n"

    private val apkSignatureVerifier = """
        .class public Landroid/util/apk/ApkSignatureVerifier;
        .super Ljava/lang/Object;

        .method public static blacklist getMinimumSignatureSchemeVersionForTargetSdk(I)I
            .registers 2
            .param p0, "targetSdk"    # I

            .line 738
            const/16 v0, 0x1e

            if-lt p0, v0, :cond_0

            return p0

            :cond_0
            return p0
        .end method

        .method private static blacklist verifyV3AndBelowSignatures(Landroid/content/pm/parsing/result/ParseInput;Ljava/lang/String;IZ)Landroid/content/pm/parsing/result/ParseResult;
            .registers 9
            .param p3, "verifyFull"    # Z

            :cond_3
            invoke-static {p0, p1, p3}, Landroid/util/apk/ApkSignatureVerifier;->verifyV1Signature(Landroid/content/pm/parsing/result/ParseInput;Ljava/lang/String;Z)Landroid/content/pm/parsing/result/ParseResult;

            move-result-object v0

            return-object v0
        .end method
    """.trimIndent() + "\n"

    private val strictJarVerifier = """
        .class Landroid/util/jar/StrictJarVerifier;
        .super Ljava/lang/Object;

        .method private static blacklist verifyMessageDigest([B[B)Z
            .registers 4

            .line 410
            const/4 v0, 0x0

            return v0
        .end method

        .method static bridge synthetic blacklist -${'$'}${'$'}Nest${'$'}smverifyMessageDigest([B[B)Z
            .registers 2

            invoke-static {p0, p1}, Landroid/util/jar/StrictJarVerifier;->verifyMessageDigest([B[B)Z

            move-result p0

            return p0
        .end method
    """.trimIndent() + "\n"

    private val strictJarFile = """
        .class public Landroid/util/jar/StrictJarFile;
        .super Ljava/lang/Object;

        .method public constructor <init>(Ljava/lang/String;ZZ)V
            .registers 11

            .line 120
            :goto_0
            invoke-interface {v4}, Ljava/util/Iterator;->hasNext()Z

            move-result v5

            if-eqz v5, :cond_1

            invoke-interface {v4}, Ljava/util/Iterator;->next()Ljava/lang/Object;

            move-result-object v5

            check-cast v5, Ljava/lang/String;

            invoke-virtual {p0, v5}, Landroid/util/jar/StrictJarFile;->findEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;

            move-result-object v6

            if-eqz v6, :cond_0

            .line 126
            goto :goto_0

            .line 124
            :cond_0
            new-instance v0, Ljava/security/SecurityException;

            const-string v6, "File "

            invoke-direct {v0, v6}, Ljava/security/SecurityException;-><init>(Ljava/lang/String;)V

            throw v0

            .line 128
            :cond_1
            return-void
        .end method
    """.trimIndent() + "\n"

    private val parsingPackageUtils = """
        .class public Landroid/content/pm/parsing/ParsingPackageUtils;
        .super Ljava/lang/Object;

        .method private static blacklist checkSharedUserIdName(Ljava/lang/String;)V
            .registers 12

            .line 1125
            invoke-static {}, Landroid/content/pm/parsing/result/ParseResult;->ok()Landroid/content/pm/parsing/result/ParseResult;

            move-result v4

            if-eqz v4, :cond_1

            .line 1130
            new-instance v0, Ljava/lang/StringBuilder;

            invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

            const-string v3, "<manifest> specifies bad sharedUserId name \""

            return-void

            :cond_1
            return-void
        .end method
    """.trimIndent() + "\n"

    private val packageManagerServiceUtils = """
        .class public Lcom/android/server/pm/PackageManagerServiceUtils;
        .super Ljava/lang/Object;

        .method public static blacklist checkDowngrade(Landroid/content/pm/PackageInfo;Landroid/content/pm/PackageInfo;)V
            .registers 14

            .line 512
            const-string v0, "downgrade"

            return-void
        .end method

        .method public static blacklist checkDowngrade(Ljava/io/File;Ljava/lang/String;Ljava/lang/String;)V
            .registers 8

            .line 544
            const-string v0, "downgrade"

            return-void
        .end method

        .method public static compareSignatures(Landroid/content/pm/SigningDetails;Landroid/content/pm/SigningDetails;)I
            .registers 4

            const/4 v0, -3

            return v0
        .end method

        .method public static matchSignaturesCompat(Landroid/content/pm/SigningDetails;Landroid/content/pm/SigningDetails;)Z
            .registers 14

            const/4 v0, 0x0

            return v0
        .end method

        .method public static verifySignatures(Landroid/content/pm/PackageParser${'$'}Package;Landroid/content/pm/pkg/AndroidPackage;Landroid/content/pm/SigningDetails;Z)Z
            .registers 15

            const/4 v0, 0x0

            return v0
        .end method
    """.trimIndent() + "\n"

    private val keySetManagerService = """
        .class public Lcom/android/server/pm/KeySetManagerService;
        .super Ljava/lang/Object;

        .method public shouldCheckUpgradeKeySetLocked(Landroid/content/pm/PackageSetting;Landroid/content/pm/PackageSetting;)Z
            .registers 10

            .line 731
            const/4 v0, 0x1

            return v0
        .end method
    """.trimIndent() + "\n"

    private val installPackageHelper = """
        .class public Lcom/android/server/pm/InstallPackageHelper;
        .super Ljava/lang/Object;

        .method private verifySharedUser(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/android/server/pm/pkg/AndroidPackage;)Z
            .registers 8

            .line 5631
            invoke-interface {p5}, Lcom/android/server/pm/pkg/AndroidPackage;->isLeavingSharedUser()Z

            move-result v3

            if-nez v3, :cond_2

            const/4 v3, 0x0

            :cond_2
            return v3
        .end method

        .method private static blacklist reconcileSharedUser(Ljava/util/List;Lcom/android/server/pm/pkg/AndroidPackage;)V
            .registers 8

            .line 5580
            invoke-interface {p1}, Lcom/android/server/pm/pkg/AndroidPackage;->getSharedUserId()Ljava/lang/String;

            move-result-object v0

            if-eqz v0, :cond_1

            .line 5590
            invoke-interface {p1}, Lcom/android/server/pm/pkg/AndroidPackage;->isLeavingSharedUser()Z

            move-result v0

            if-nez v0, :cond_1

            .line 5591
            return-void

            :cond_1
            return-void
        .end method
    """.trimIndent() + "\n"

    private val reconcilePackageUtils = """
        .class public Lcom/android/server/pm/ReconcilePackageUtils;
        .super Ljava/lang/Object;

        .field private static final ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS:Z

        .method static constructor <clinit>()V
            .registers 1

            .line 62
            sget-boolean v0, Landroid/os/Build;->IS_DEBUGGABLE:Z

            if-nez v0, :cond_1

            invoke-static {}, Lcom/android/internal/hidden_from_bootclasspath/android/content/pm/Flags;->restrictNonpreloadsSystemShareduids()Z

            move-result v0

            if-nez v0, :cond_0

            goto :goto_0

            :cond_0
            const/4 v0, 0x0

            goto :goto_1

            :cond_1
            :goto_0
            const/4 v0, 0x1

            :goto_1
            sput-boolean v0, Lcom/android/server/pm/ReconcilePackageUtils;->ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS:Z

            return-void
        .end method

        .method public static blacklist isAllowed(Ljava/lang/String;)Z
            .registers 2

            const/4 v0, 0x0

            return v0
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `the engine exposes all sixteen CorePatch targets for FULL mode`() {
        assertEquals(16, PatchEngine.COREPATCH_TARGETS.size)
        assertEquals(PatchEngine.COREPATCH_FILES.toSet(), PatchEngine.COREPATCH_TARGETS.keys)
        assertTrue(
            PatchEngine.COREPATCH_TARGETS.keys.all { it in PatchEngine.disassemblyFiles(PatchMode.FULL) },
            "a target that is never disassembled can never be patched",
        )
        assertTrue(PatchEngine.DSV_TARGETS.keys.all { it in PatchEngine.disassemblyFiles(PatchMode.FULL) })
    }

    @Test
    fun `packageParser forces the cert version and lets a bad sharedUserId name pass`() {
        val patched = CorePatchPatch.patchPackageParser(packageParser)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyPackageParser(patched.content)

        assertTrue(
            Regex("""const/4 v1, 0x1\n\s+invoke-static \{v2, v0, v1},\s*Landroid/util/apk/ApkSignatureVerifier""")
                .containsMatchIn(patched.content),
            "the version handed to unsafeGetCertsWithoutVerification was not forced to 1",
        )
        assertTrue(
            Regex("""const/4 v0, 0x1\n\s+if-nez v0, :cond_1""").containsMatchIn(patched.content),
            "if-nez must be forced taken, i.e. const 1",
        )
    }

    @Test
    fun `packageParser is idempotent and fails closed without its anchor`() {
        val once = CorePatchPatch.patchPackageParser(packageParser)
        val twice = CorePatchPatch.patchPackageParser(once.content)
        assertEquals(PatchStatus.ALREADY_PATCHED, twice.status)
        assertEquals(once.content, twice.content)

        val broken = packageParser.replace(
            Regex("invoke-static[^\\n]*unsafeGetCertsWithoutVerification[^\\n]*\n"),
            "",
        )
        assertFalse(broken.contains("unsafeGetCertsWithoutVerification"))
        assertFailsWith<UnsupportedLayoutException> { CorePatchPatch.patchPackageParser(broken) }
    }

    @Test
    fun `packageParserException forces every store of the error field`() {
        val patched = CorePatchPatch.patchPackageParserException(packageParserException)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyPackageParserException(patched.content)

        assertEquals(
            2,
            Regex("""const/4 p1, 0x0\n\s+iput p1, p0, Landroid/content/pm/PackageParser""")
                .findAll(patched.content).count(),
            "both constructors must store 0, inserted above the iput the guide names",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchPackageParserException(patched.content).status,
        )
    }

    @Test
    fun `both signing detail classes answer true from every capability check`() {
        val parsed = CorePatchPatch.patchPackageParserSigningDetails(signingDetails)
        assertEquals(PatchStatus.PATCHED, parsed.status)
        CorePatchPatch.verifyPackageParserSigningDetails(parsed.content)
        assertEquals(
            3,
            Regex("""^\s+return p0$""", RegexOption.MULTILINE).findAll(parsed.content).count(),
            "the PackageParser patcher must leave hasAncestorOrSelf alone",
        )
        assertTrue(
            Regex(
                """hasAncestorOrSelf\(Landroid/content/pm/SigningDetails;\)Z\n\s+\.registers 5\n\s+const/4 v0, 0x0""",
            ).containsMatchIn(parsed.content),
            "hasAncestorOrSelf's own body was disturbed",
        )

        val full = CorePatchPatch.patchSigningDetails(signingDetails)
        assertEquals(PatchStatus.PATCHED, full.status)
        CorePatchPatch.verifySigningDetails(full.content)
        assertEquals(
            4,
            Regex("""^\s+return p0$""", RegexOption.MULTILINE).findAll(full.content).count(),
            "the three capability checks plus hasAncestorOrSelf must be forced",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchSigningDetails(full.content).status,
        )
    }

    @Test
    fun `the digest verdict is replaced by a constant in all three classes`() {
        for (name in listOf("ApkSignatureSchemeV2Verifier", "ApkSignatureSchemeV3Verifier", "ApkSigningBlockUtils")) {
            val source = digestVerifier(name)
            val patched = when (name) {
                "ApkSignatureSchemeV2Verifier" -> CorePatchPatch.patchApkSignatureSchemeV2Verifier(source)
                "ApkSignatureSchemeV3Verifier" -> CorePatchPatch.patchApkSignatureSchemeV3Verifier(source)
                else -> CorePatchPatch.patchApkSigningBlockUtils(source)
            }
            assertEquals(PatchStatus.PATCHED, patched.status, name)
            assertTrue(
                Regex("""isEqual\(\[B\[B\)Z\n\s+const/4 v0, 0x1""").containsMatchIn(patched.content),
                "$name: the move-result was not replaced by the forced verdict",
            )
            assertFalse(Regex("""isEqual\(\[B\[B\)Z\n\s+move-result""").containsMatchIn(patched.content))
            assertEquals(
                PatchStatus.ALREADY_PATCHED,
                (when (name) {
                    "ApkSignatureSchemeV2Verifier" -> CorePatchPatch.patchApkSignatureSchemeV2Verifier(patched.content)
                    "ApkSignatureSchemeV3Verifier" -> CorePatchPatch.patchApkSignatureSchemeV3Verifier(patched.content)
                    else -> CorePatchPatch.patchApkSigningBlockUtils(patched.content)
                }).status,
                "$name",
            )
        }
    }

    @Test
    fun `apkSignatureVerifier forces the minimum scheme and the verifyFull guard`() {
        val patched = CorePatchPatch.patchApkSignatureVerifier(apkSignatureVerifier)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyApkSignatureVerifier(patched.content)

        assertTrue(
            Regex("""getMinimumSignatureSchemeVersionForTargetSdk\(I\)I\n\s+\.registers 2\n\s+const/4 p0, 0x0\n\s+return p0""")
                .containsMatchIn(patched.content),
            "the minimum scheme was not forced to 0",
        )
        assertTrue(
            Regex("""const/4 p3, 0x0\n\s+invoke-static \{p0, p1, p3},\s*Landroid/util/apk/ApkSignatureVerifier;->verifyV1Signature""")
                .containsMatchIn(patched.content),
            "verifyV1Signature's verifyFull argument was not forced to 0",
        )
    }

    @Test
    fun `strictJarVerifier answers true without touching its bridge method`() {
        val patched = CorePatchPatch.patchStrictJarVerifier(strictJarVerifier)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyStrictJarVerifier(patched.content)

        assertTrue(
            Regex("""private static blacklist verifyMessageDigest\(\[B\[B\)Z\n\s+\.registers 4\n\s+const/4 p0, 0x1\n\s+return p0""")
                .containsMatchIn(patched.content),
            "verifyMessageDigest was not forced true",
        )
        assertTrue(
            Regex("""smverifyMessageDigest\(\[B\[B\)Z\n\s+\.registers 2\n\s+invoke-static""")
                .containsMatchIn(patched.content),
            "the synthetic bridge shares a name suffix and must not be rewritten",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchStrictJarVerifier(patched.content).status,
        )
    }

    @Test
    fun `strictJarFile stops throwing on a missing manifest entry`() {
        val patched = CorePatchPatch.patchStrictJarFile(strictJarFile)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyStrictJarFile(patched.content)

        assertFalse(patched.content.contains(":cond_0"), "the label only served the throw")
        assertTrue(patched.content.contains("goto :goto_0"), "the loop itself must survive")
        assertTrue(patched.content.contains("new-instance v0, Ljava/security/SecurityException;"))
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchStrictJarFile(patched.content).status,
        )
    }

    @Test
    fun `strictJarFile refuses a label the method still needs`() {
        val shared = strictJarFile.replace(
            "    .line 126\n    goto :goto_0",
            "    goto :cond_0\n\n    .line 126\n    goto :goto_0",
        )
        assertTrue(shared.contains("goto :cond_0"))
        assertFailsWith<UnsupportedLayoutException> { CorePatchPatch.patchStrictJarFile(shared) }
        assertFailsWith<PatchVerificationException> { CorePatchPatch.verifyStrictJarFile(strictJarFile) }
    }

    @Test
    fun `sharedUserId branches are forced whichever conditional the ROM spells`() {
        val parser = CorePatchPatch.patchPackageParser(packageParser)
        assertTrue(Regex("""const/4 v0, 0x1\n\s+if-nez v0, :cond_1""").containsMatchIn(parser.content))

        val parsing = CorePatchPatch.patchParsingPackageUtils(parsingPackageUtils)
        assertEquals(PatchStatus.PATCHED, parsing.status)
        CorePatchPatch.verifyParsingPackageUtils(parsing.content)
        assertTrue(
            Regex("""const/4 v4, 0x0\n\s+if-eqz v4, :cond_1""").containsMatchIn(parsing.content),
            "if-eqz must be forced taken, i.e. const 0",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchParsingPackageUtils(parsing.content).status,
        )
    }

    @Test
    fun `packageManagerServiceUtils pins downgrade and every signature check`() {
        val patched = CorePatchPatch.patchPackageManagerServiceUtils(packageManagerServiceUtils)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyPackageManagerServiceUtils(patched.content)

        assertEquals(
            2,
            Regex("""checkDowngrade\([^)]*\)V\n\s+\.registers \d+\n\s+return-void""")
                .findAll(patched.content).count(),
            "every checkDowngrade overload must return immediately",
        )
        assertTrue(patched.content.contains("const/4 p0, 0x0\n    return p0"))
        assertTrue(patched.content.contains("const/4 p0, 0x1\n    return p0"))
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchPackageManagerServiceUtils(patched.content).status,
        )
    }

    @Test
    fun `keySetManagerService answers false`() {
        val patched = CorePatchPatch.patchKeySetManagerService(keySetManagerService)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyKeySetManagerService(patched.content)
        assertTrue(
            Regex("""shouldCheckUpgradeKeySetLocked\([^)]*\)Z\n\s+\.registers 10\n\s+const/4 p0, 0x0\n\s+return p0""")
                .containsMatchIn(patched.content),
        )
    }

    @Test
    fun `installPackageHelper forces the leaving-shared-user branch`() {
        val patched = CorePatchPatch.patchInstallPackageHelper(installPackageHelper)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyInstallPackageHelper(patched.content)
        assertTrue(
            Regex("""const/4 v0, 0x1\n\s+if-nez v0, :cond_1""").containsMatchIn(patched.content),
            "the isLeavingSharedUser branch was not forced",
        )
        assertFalse(
            Regex("""const/4 v3, 0x1\n\s+if-nez v3, :cond_2""").containsMatchIn(patched.content),
            "the guide anchors on the p1 site; the p5 call is another method's",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchInstallPackageHelper(patched.content).status,
        )
    }

    @Test
    fun `reconcilePackageUtils flips the constant inside clinit and only there`() {
        val patched = CorePatchPatch.patchReconcilePackageUtils(reconcilePackageUtils)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyReconcilePackageUtils(patched.content)

        assertTrue(
            Regex("""isAllowed\(Ljava/lang/String;\)Z\n\s+\.registers 2\n\s+const/4 v0, 0x0""")
                .containsMatchIn(patched.content),
            "the rewrite escaped <clinit> and reached an unrelated method",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchReconcilePackageUtils(patched.content).status,
        )

        val secondConstant = reconcilePackageUtils.replace(
            "    if-nez v0, :cond_1\n",
            "    const/4 v0, 0x0\n\n    if-nez v0, :cond_1\n",
        )
        assertFailsWith<UnsupportedLayoutException> {
            CorePatchPatch.patchReconcilePackageUtils(secondConstant)
        }
    }

    @Test
    fun `verification rejects stock output rather than passing on it`() {
        assertFailsWith<PatchVerificationException> { CorePatchPatch.verifyPackageParser(packageParser) }
        assertFailsWith<PatchVerificationException> { CorePatchPatch.verifyStrictJarFile(strictJarFile) }
        assertFailsWith<PatchVerificationException> {
            CorePatchPatch.verifyReconcilePackageUtils(reconcilePackageUtils)
        }
        assertFailsWith<PatchVerificationException> {
            CorePatchPatch.verifyApkSignatureVerifier(apkSignatureVerifier)
        }
    }

    private val packageManagerServiceImpl = """
        .class public Lcom/android/server/pm/PackageManagerServiceImpl;
        .super Ljava/lang/Object;

        .method private verifyIsolationViolation(Lcom/android/internal/pm/parsing/pkg/ParsedPackage;Lcom/android/server/pm/InstallSource;)V
            .registers 16
            .param p1, "pkg"    # Lcom/android/internal/pm/parsing/pkg/ParsedPackage;
            .param p2, "source"    # Lcom/android/server/pm/InstallSource;
            .annotation system Ldalvik/annotation/Throws;
                value = {
                    Lcom/android/server/pm/PrepareFailure;
                }
            .end annotation

            .line 2491
            invoke-interface {p1}, Lcom/android/internal/pm/parsing/pkg/ParsedPackage;->getPackageName()Ljava/lang/String;

            return-void
        .end method

        .method public canBeUpdate(Ljava/lang/String;)V
            .registers 7
            .param p1, "packageName"    # Ljava/lang/String;

            .line 2328
            sget-boolean v0, Landroid/os/Build;->IS_DEBUGGABLE:Z

            return-void
        .end method
    """.trimIndent() + "\n"

    @Test
    fun `section 3 forces both update gates to return-void`() {
        val patched = CorePatchPatch.patchPackageManagerServiceImpl(packageManagerServiceImpl)
        assertEquals(PatchStatus.PATCHED, patched.status)
        CorePatchPatch.verifyPackageManagerServiceImpl(patched.content)

        assertTrue(
            patched.content.contains(".registers 16\n    return-void\n    .param p1, \"pkg\""),
            "verifyIsolationViolation was not forced before its params and annotations",
        )
        assertTrue(
            patched.content.contains(".registers 7\n    return-void\n    .param p1, \"packageName\""),
            "canBeUpdate was not forced",
        )
        assertEquals(
            PatchStatus.ALREADY_PATCHED,
            CorePatchPatch.patchPackageManagerServiceImpl(patched.content).status,
        )
    }

    @Test
    fun `section 3 reports an absence instead of rejecting it`() {
        val withoutSection = """
            .class public Lcom/android/server/pm/PackageManagerServiceImpl;
            .super Ljava/lang/Object;

            .method public somethingElse()V
                .registers 1

                return-void
            .end method
        """.trimIndent() + "\n"

        val outcome = CorePatchPatch.patchPackageManagerServiceImpl(withoutSection)
        assertEquals(PatchStatus.NOT_TARGET, outcome.status)
        assertEquals(withoutSection, outcome.content, "a skipped section must leave the file byte-identical")
        assertFailsWith<PatchVerificationException> {
            CorePatchPatch.verifyPackageManagerServiceImpl(withoutSection)
        }
        assertFailsWith<PatchVerificationException> {
            CorePatchPatch.verifyPackageManagerServiceImpl(packageManagerServiceImpl)
        }
    }
}
