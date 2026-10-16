.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;
.source "AndroidKeyStoreSpi.java"

.method public whitelist test-api engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
    .registers 4

    .line 222
    invoke-direct {p0, p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->getKeyMetadata(Ljava/lang/String;)Landroid/system/keystore2/KeyEntryResponse;

    move-result-object p1

    .line 224
    const/4 v0, 0x0

    if-nez p1, :cond_8

    .line 225
    return-object v0

    .line 228
    :cond_8
    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    .line 229
    if-eqz v1, :cond_13

    .line 230
    invoke-static {v1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object p1

    return-object p1

    .line 233
    :cond_13
    iget-object p1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object p1, p1, Landroid/system/keystore2/KeyMetadata;->certificateChain:[B

    .line 234
    if-eqz p1, :cond_1e

    .line 235
    invoke-static {p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object p1

    return-object p1

    .line 239
    :cond_1e
    return-object v0
.end method

.method public whitelist test-api engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .registers 7

    .line 186
    invoke-direct {p0, p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->getKeyMetadata(Ljava/lang/String;)Landroid/system/keystore2/KeyEntryResponse;

    move-result-object p1

    .line 188
    const/4 v0, 0x0

    if-eqz p1, :cond_4d

    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    if-nez v1, :cond_e

    goto :goto_4d

    .line 192
    :cond_e
    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    invoke-static {v1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object v1

    .line 193
    if-nez v1, :cond_19

    .line 194
    return-object v0

    .line 199
    :cond_19
    iget-object p1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object p1, p1, Landroid/system/keystore2/KeyMetadata;->certificateChain:[B

    .line 201
    const/4 v0, 0x1

    if-eqz p1, :cond_43

    .line 202
    invoke-static {p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificates([B)Ljava/util/Collection;

    move-result-object p1

    .line 204
    invoke-interface {p1}, Ljava/util/Collection;->size()I

    move-result v2

    add-int/2addr v2, v0

    new-array v2, v2, [Ljava/security/cert/Certificate;

    .line 206
    invoke-interface {p1}, Ljava/util/Collection;->iterator()Ljava/util/Iterator;

    move-result-object p1

    .line 207
    nop

    .line 208
    :goto_30
    invoke-interface {p1}, Ljava/util/Iterator;->hasNext()Z

    move-result v3

    if-eqz v3, :cond_42

    .line 209
    add-int/lit8 v3, v0, 0x1

    invoke-interface {p1}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v4

    check-cast v4, Ljava/security/cert/Certificate;

    aput-object v4, v2, v0

    move v0, v3

    goto :goto_30

    .line 211
    :cond_42
    goto :goto_45

    .line 212
    :cond_43
    new-array v2, v0, [Ljava/security/cert/Certificate;

    .line 215
    :goto_45
    const/4 p1, 0x0

    aput-object v1, v2, p1

    .line 217
    invoke-static {v2}, Landroid/security/keystore2/AndroidKeyStoreSpi;->hackCertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;

    move-result-object p1

    return-object p1

    .line 189
    :cond_4d
    :goto_4d
    return-object v0
.end method
