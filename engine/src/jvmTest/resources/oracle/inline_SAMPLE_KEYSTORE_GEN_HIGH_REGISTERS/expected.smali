.class public Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;
.super Ljava/lang/Object;

.method public generateKeyPair()Ljava/security/KeyPair;
    .registers 21
    invoke-static/range {p0 .. p0}, Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;
    move-result-object v19

    if-eqz v19, :cond_kaorios_gen_stock
    return-object v19

    :cond_kaorios_gen_stock

    const/4 v0, 0x0
    return-object v0
.end method
