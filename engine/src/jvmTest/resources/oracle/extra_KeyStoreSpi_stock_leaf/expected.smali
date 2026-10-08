.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;
.method public engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
    .locals 2
    invoke-virtual/range {p0 .. p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    move-result-object v0
    if-eqz v0, :kaorios_certificate_stock
    array-length v1, v0
    if-eqz v1, :kaorios_certificate_stock
    const/4 v1, 0x0
    aget-object v0, v0, v1
    return-object v0
    :kaorios_certificate_stock

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
    invoke-static {v1}, Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
    move-result-object v1
    return-object v1
.end method
