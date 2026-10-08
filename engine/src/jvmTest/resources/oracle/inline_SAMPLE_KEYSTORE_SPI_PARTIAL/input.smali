.class public Landroid/security/AndroidKeyStoreSpi;
.super Ljava/lang/Object;

.method public engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .locals 4

    if-eqz p1, :cond_null

    aput-object v0, v1, v2
    invoke-static {v1}, Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
    move-result-object v1
    return-object v1

    :cond_null
    aput-object v0, v3, v2
    return-object v3
.end method
