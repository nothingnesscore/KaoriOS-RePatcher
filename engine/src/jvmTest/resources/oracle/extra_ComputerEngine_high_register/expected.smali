.class public Lcom/android/server/pm/ComputerEngine;
.super Ljava/lang/Object;

.method public final shouldFilterApplication(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ)Z
    .locals 28
    .param p1, "ps"    # Lcom/android/server/pm/pkg/PackageStateInternal;
    .param p2, "callingUid"    # I
    move-object/16 v17, p0
    move-object/16 v18, p1
    move/16 v19, p2
    move-object/16 v20, p3
    move/16 v21, p4
    move/16 v22, p5
    move/16 v23, p6
    move/16 v24, p7
    if-eqz v18, :cond_kaorios_ps_null
    invoke-interface/range {v18 .. v18}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;
    move-result-object v26
    if-eqz v26, :cond_kaorios_ps_null
    move/16 v25, v19
    move/16 v27, v22
    invoke-static/range {v25 .. v27}, Landroid/security/kaorios/KaoriosHook;->shouldHideAppListForCaller(ILjava/lang/String;I)Z
    move-result v25
    if-eqz v25, :cond_kaorios_ps_null
    const/16 v25, 0x1
    return v25
    :cond_kaorios_ps_null

    .line 2566
    move-object/from16 v6, v17
    move-object/from16 v7, v18
    const/4 v0, 0x0
    return v0
.end method

