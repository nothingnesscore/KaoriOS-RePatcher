.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;
.source "ComputerEngine.java"

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .locals 1
    .param p1, "ps"    # Lcom/android/server/pm/pkg/PackageStateInternal;
    .param p2, "callingUid"    # I
    .param p3, "component"    # Landroid/content/ComponentName;
    .param p4, "componentType"    # I
    .param p5, "userId"    # I
    .param p6, "filterUninstall"    # Z
    .param p7, "filterArchived"    # Z
    if-eqz p1, :cond_kaorios_ps_null
    invoke-interface {p1}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;
    move-result-object v0
    if-eqz v0, :cond_kaorios_ps_null
    invoke-static {p2, v0, p5}, Landroid/security/kaorios/KaoriosHook;->shouldHideAppListForCaller(ILjava/lang/String;I)Z
    move-result v0
    if-eqz v0, :cond_kaorios_ps_null
    const/4 v0, 0x1
    return v0
    :cond_kaorios_ps_null

    const/4 v0, 0x0
    return v0
.end method
