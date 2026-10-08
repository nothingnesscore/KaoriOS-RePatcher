.class public Landroid/app/ApplicationPackageManager;
.super Ljava/lang/Object;

.method public hasSystemFeature(Ljava/lang/String;I)Z
    .registers 6
    invoke-static {p1, p2}, Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;
    move-result-object v2

    if-eqz v2, :cond_kaorios_feature_stock
    invoke-virtual {v2}, Ljava/lang/Boolean;->booleanValue()Z
    move-result v2
    return v2

    :cond_kaorios_feature_stock
    const/4 v0, 0x0
    const/4 v1, 0x1
    return v0
.end method
