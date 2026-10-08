.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;
.source "ComputerEngine.java"

.method public getInstallSourceInfo(Ljava/lang/String;I)Landroid/content/pm/InstallSourceInfo;
    .registers 20

    move-object/from16 v6, p0

    move-object/from16 v7, p1

    move/from16 v8, p2

    .line 5318
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I

    move-result v9

    const/4 v4, 0x0

    .line 5319
    const-string v5, "getInstallSourceInfo"

    const/4 v3, 0x0

    move-object/from16 v0, p0

    move v1, v9

    move/from16 v2, p2

    invoke-virtual/range {v0 .. v5}, Lcom/android/server/pm/ComputerEngine;->enforceCrossUserPermission(IIZZLjava/lang/String;)V

    .line 5327
    invoke-virtual {v6, v7, v9, v8}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;

    move-result-object v0

    const/4 v1, 0x0

    if-nez v0, :cond_0

    return-object v1

    .line 5332
    :cond_0
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;

    if-eqz v2, :cond_2

    .line 5334
    iget-object v3, v6, Lcom/android/server/pm/ComputerEngine;->mSettings:Lcom/android/server/pm/ComputerEngine$Settings;

    invoke-virtual {v3, v2}, Lcom/android/server/pm/ComputerEngine$Settings;->getPackage(Ljava/lang/String;)Lcom/android/server/pm/pkg/PackageStateInternal;

    move-result-object v3

    if-eqz v3, :cond_1

    .line 5336
    invoke-virtual {v6, v3, v9, v8}, Lcom/android/server/pm/ComputerEngine;->shouldFilterApplicationIncludingUninstalled(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z

    move-result v3

    if-eqz v3, :cond_2

    :cond_1
    move-object v2, v1

    .line 5341
    :cond_2
    iget-object v3, v0, Lcom/android/server/pm/InstallSource;->mUpdateOwnerPackageName:Ljava/lang/String;

    if-eqz v3, :cond_6

    .line 5343
    iget-object v4, v6, Lcom/android/server/pm/ComputerEngine;->mSettings:Lcom/android/server/pm/ComputerEngine$Settings;

    invoke-virtual {v4, v3}, Lcom/android/server/pm/ComputerEngine$Settings;->getPackage(Ljava/lang/String;)Lcom/android/server/pm/pkg/PackageStateInternal;

    move-result-object v4

    const/16 v5, 0x3e8

    if-eq v9, v5, :cond_4

    .line 5345
    invoke-virtual {v6, v3, v9}, Lcom/android/server/pm/ComputerEngine;->isCallerSameApp(Ljava/lang/String;I)Z

    move-result v5

    if-eqz v5, :cond_3

    goto :goto_0

    :cond_3
    const/4 v5, 0x0

    goto :goto_1

    :cond_4
    :goto_0
    const/4 v5, 0x1

    :goto_1
    if-eqz v4, :cond_5

    .line 5351
    invoke-virtual {v6, v4, v9, v8}, Lcom/android/server/pm/ComputerEngine;->shouldFilterApplicationIncludingUninstalled(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z

    move-result v4

    if-nez v4, :cond_5

    if-nez v5, :cond_6

    .line 5352
    invoke-virtual {v6, v8}, Lcom/android/server/pm/ComputerEngine;->isCallerFromManagedUserOrProfile(I)Z

    move-result v4

    if-eqz v4, :cond_6

    :cond_5
    move-object v15, v1

    goto :goto_2

    :cond_6
    move-object v15, v3

    .line 5357
    :goto_2
    const-string v3, "com.aurora.store"

    invoke-virtual {v3, v2}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v3

    if-eqz v3, :cond_7

    .line 5358
    const-string v2, "com.android.vending"

    :cond_7
    move-object v14, v2

    .line 5361
    iget-boolean v2, v0, Lcom/android/server/pm/InstallSource;->mIsInitiatingPackageUninstalled:Z

    if-eqz v2, :cond_b

    .line 5366
    invoke-virtual {v6, v9}, Lcom/android/server/pm/ComputerEngine;->getInstantAppPackageName(I)Ljava/lang/String;

    move-result-object v2

    if-eqz v2, :cond_8

    goto :goto_3

    .line 5367
    :cond_8
    invoke-virtual {v6, v7, v9}, Lcom/android/server/pm/ComputerEngine;->isCallerSameApp(Ljava/lang/String;I)Z

    move-result v2

    if-eqz v2, :cond_9

    .line 5368
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mInitiatingPackageName:Ljava/lang/String;

    goto :goto_4

    :cond_9
    :goto_3
    move-object v2, v1

    :cond_a
    :goto_4
    move-object v11, v2

    goto :goto_5

    .line 5373
    :cond_b
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mInitiatingPackageName:Ljava/lang/String;

    iget-object v3, v0, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;

    invoke-static {v2, v3}, Ljava/util/Objects;->equals(Ljava/lang/Object;Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :cond_c

    move-object v11, v14

    goto :goto_5

    .line 5379
    :cond_c
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mInitiatingPackageName:Ljava/lang/String;

    .line 5380
    iget-object v3, v6, Lcom/android/server/pm/ComputerEngine;->mSettings:Lcom/android/server/pm/ComputerEngine$Settings;

    invoke-virtual {v3, v2}, Lcom/android/server/pm/ComputerEngine$Settings;->getPackage(Ljava/lang/String;)Lcom/android/server/pm/pkg/PackageStateInternal;

    move-result-object v3

    if-eqz v3, :cond_d

    .line 5382
    invoke-virtual {v6, v3, v9, v8}, Lcom/android/server/pm/ComputerEngine;->shouldFilterApplicationIncludingUninstalled(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z

    move-result v3

    if-eqz v3, :cond_a

    :cond_d
    move-object v11, v1

    .line 5388
    :goto_5
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mOriginatingPackageName:Ljava/lang/String;

    if-eqz v2, :cond_f

    .line 5390
    iget-object v3, v6, Lcom/android/server/pm/ComputerEngine;->mSettings:Lcom/android/server/pm/ComputerEngine$Settings;

    invoke-virtual {v3, v2}, Lcom/android/server/pm/ComputerEngine$Settings;->getPackage(Ljava/lang/String;)Lcom/android/server/pm/pkg/PackageStateInternal;

    move-result-object v3

    if-eqz v3, :cond_e

    .line 5392
    invoke-virtual {v6, v3, v9, v8}, Lcom/android/server/pm/ComputerEngine;->shouldFilterApplicationIncludingUninstalled(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z

    move-result v3

    if-eqz v3, :cond_f

    :cond_e
    move-object v2, v1

    :cond_f
    if-eqz v2, :cond_10

    .line 5400
    iget-object v3, v6, Lcom/android/server/pm/ComputerEngine;->mContext:Landroid/content/Context;

    const-string v4, "android.permission.INSTALL_PACKAGES"

    invoke-virtual {v3, v4}, Landroid/content/Context;->checkCallingOrSelfPermission(Ljava/lang/String;)I

    move-result v3

    if-eqz v3, :cond_10

    move-object v13, v1

    goto :goto_6

    :cond_10
    move-object v13, v2

    .line 5408
    :goto_6
    iget-object v2, v0, Lcom/android/server/pm/InstallSource;->mInitiatingPackageSignatures:Lcom/android/server/pm/PackageSignatures;

    if-eqz v11, :cond_11

    if-eqz v2, :cond_11

    .line 5409
    iget-object v3, v2, Lcom/android/server/pm/PackageSignatures;->mSigningDetails:Landroid/content/pm/SigningDetails;

    sget-object v4, Landroid/content/pm/SigningDetails;->UNKNOWN:Landroid/content/pm/SigningDetails;

    if-eq v3, v4, :cond_11

    .line 5411
    new-instance v1, Landroid/content/pm/SigningInfo;

    iget-object v2, v2, Lcom/android/server/pm/PackageSignatures;->mSigningDetails:Landroid/content/pm/SigningDetails;

    invoke-direct {v1, v2}, Landroid/content/pm/SigningInfo;-><init>(Landroid/content/pm/SigningDetails;)V

    :cond_11
    move-object v12, v1

    .line 5416
    new-instance v1, Landroid/content/pm/InstallSourceInfo;

    iget v0, v0, Lcom/android/server/pm/InstallSource;->mPackageSource:I

    move-object v10, v1

    move/from16 v16, v0

    invoke-direct/range {v10 .. v16}, Landroid/content/pm/InstallSourceInfo;-><init>(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V

    return-object v1
.end method

.method public getInstallerPackageName(Ljava/lang/String;I)Ljava/lang/String;
    .registers 5

    .line 5264
    invoke-static {}, Landroid/os/Binder;->getCallingUid()I

    move-result v0

    .line 5265
    invoke-virtual {p0, p1, v0, p2}, Lcom/android/server/pm/ComputerEngine;->getInstallSource(Ljava/lang/String;II)Lcom/android/server/pm/InstallSource;

    move-result-object p2

    if-eqz p2, :cond_2

    .line 5269
    iget-object p1, p2, Lcom/android/server/pm/InstallSource;->mInstallerPackageName:Ljava/lang/String;

    if-eqz p1, :cond_1

    .line 5271
    iget-object p2, p0, Lcom/android/server/pm/ComputerEngine;->mSettings:Lcom/android/server/pm/ComputerEngine$Settings;

    invoke-virtual {p2, p1}, Lcom/android/server/pm/ComputerEngine$Settings;->getPackage(Ljava/lang/String;)Lcom/android/server/pm/pkg/PackageStateInternal;

    move-result-object p2

    if-eqz p2, :cond_0

    .line 5273
    invoke-static {v0}, Landroid/os/UserHandle;->getUserId(I)I

    move-result v1

    .line 5272
    invoke-virtual {p0, p2, v0, v1}, Lcom/android/server/pm/ComputerEngine;->shouldFilterApplicationIncludingUninstalledNotArchived(Lcom/android/server/pm/pkg/PackageStateInternal;II)Z

    move-result p0

    if-eqz p0, :cond_1

    :cond_0
    const/4 p1, 0x0

    :cond_1
    return-object p1

    .line 5267
    :cond_2
    new-instance p0, Ljava/lang/IllegalArgumentException;

    new-instance p2, Ljava/lang/StringBuilder;

    invoke-direct {p2}, Ljava/lang/StringBuilder;-><init>()V

    const-string v0, "Unknown package: "

    invoke-virtual {p2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p2, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0
.end method
