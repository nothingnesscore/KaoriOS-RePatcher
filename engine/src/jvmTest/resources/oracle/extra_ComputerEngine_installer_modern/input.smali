.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .registers 10
    const/4 v0, 0x0
    return v0
.end method

.method public getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
    .registers 6
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v2
    const/4 v0, 0x0
    return-object v0
.end method

.method public getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;
    .registers 7
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v2
    const/4 v0, 0x0
    return-object v0
.end method

