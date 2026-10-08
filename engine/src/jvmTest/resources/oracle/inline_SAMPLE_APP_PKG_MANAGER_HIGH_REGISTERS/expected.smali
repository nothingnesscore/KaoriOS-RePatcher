.class public Landroid/app/ApplicationPackageManager;
.super Ljava/lang/Object;

.method public hasSystemFeature(Ljava/lang/String;I)Z
    .registers 21
    invoke-static/range {p1 .. p2}, Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;
    move-result-object v17

    if-eqz v17, :cond_kaorios_feature_stock
    invoke-virtual/range {v17 .. v17}, Ljava/lang/Boolean;->booleanValue()Z
    move-result v17
    return v17

    :cond_kaorios_feature_stock
    const/4 v0, 0x0
    return v0
.end method
