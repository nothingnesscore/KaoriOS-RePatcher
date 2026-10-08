.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;
.method public engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
    .locals 2
    const/4 v0, 0x0
    return-object v0
.end method
.method public engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .locals 3
    const/4 v0, 0x1
    new-array v1, v0, [Ljava/security/cert/Certificate;
    const/4 v0, 0x0
    const/4 v2, 0x0
    aput-object v2, v1, v0
    return-object v1
.end method
