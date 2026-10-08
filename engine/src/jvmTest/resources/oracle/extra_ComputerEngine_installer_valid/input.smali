.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .registers 10
    const/4 v0, 0x0
    return v0
.end method

.method public getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
    .registers 8
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v3
    invoke-virtual {p1, p2, v3}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;
    move-result-object v2
    iget-object v4, v2, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;
    return-object v4
.end method

.method public getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;
    .registers 12
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I
    move-result v3
    invoke-virtual {p1, p2, v3}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;
    move-result-object v2
    iget-object v5, v2, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;
    const/4 v6, 0x0
    new-instance v7, Landroid/content/pm/InstallSourceInfo;
    invoke-direct {v7, v5, v6, v6, v5, v6}, Landroid/content/pm/InstallSourceInfo;-><init>(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V
    return-object v7
.end method

