.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;

.method public whitelist test-api engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;
    .registers 4

    .line 244
    invoke-direct {p0, p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->getKeyMetadata(Ljava/lang/String;)Landroid/system/keystore2/KeyEntryResponse;

    move-result-object p1

    .line 246
    const/4 v0, 0x0

    if-nez p1, :cond_0

    .line 247
    return-object v0

    .line 250
    :cond_0
    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    .line 251
    if-eqz v1, :cond_1

    .line 252
    invoke-static {v1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object p1

    return-object p1

    .line 255
    :cond_1
    iget-object p1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object p1, p1, Landroid/system/keystore2/KeyMetadata;->certificateChain:[B

    .line 256
    if-eqz p1, :cond_2

    .line 257
    invoke-static {p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object p1

    return-object p1

    .line 261
    :cond_2
    return-object v0
.end method

.method public whitelist test-api engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .registers 9

    .line 196
    invoke-static {}, Lcom/android/internal/util/android/PropsHooksUtils;->onEngineGetCertificateChain()V

    .line 197
    invoke-direct {p0, p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->getKeyMetadata(Ljava/lang/String;)Landroid/system/keystore2/KeyEntryResponse;

    move-result-object p1

    .line 199
    const/4 v0, 0x0

    if-eqz p1, :cond_5

    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    if-nez v1, :cond_0

    goto :goto_2

    .line 203
    :cond_0
    iget-object v1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object v1, v1, Landroid/system/keystore2/KeyMetadata;->certificate:[B

    invoke-static {v1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificate([B)Ljava/security/cert/X509Certificate;

    move-result-object v1

    .line 204
    if-nez v1, :cond_1

    .line 205
    return-object v0

    .line 208
    :cond_1
    nop

    .line 210
    :try_start_0
    invoke-virtual {v1}, Ljava/security/cert/X509Certificate;->getEncoded()[B

    move-result-object v2

    .line 211
    const/4 v3, 0x0

    const/4 v4, 0x1

    if-eqz v2, :cond_2

    array-length v5, v2

    if-lez v5, :cond_2

    .line 212
    invoke-static {v2}, Landroid/security/keystore2/AndroidKeyStoreSpi;->indexOf([B)I

    move-result v5

    .line 213
    const/4 v6, -0x1

    if-eq v5, v6, :cond_2

    .line 214
    add-int/lit8 v1, v5, 0x26

    aput-byte v4, v2, v1

    .line 215
    add-int/lit8 v5, v5, 0x29

    aput-byte v3, v2, v5

    .line 216
    const-string v1, "X.509"

    invoke-static {v1}, Ljava/security/cert/CertificateFactory;->getInstance(Ljava/lang/String;)Ljava/security/cert/CertificateFactory;

    move-result-object v1

    .line 217
    new-instance v5, Ljava/io/ByteArrayInputStream;

    invoke-direct {v5, v2}, Ljava/io/ByteArrayInputStream;-><init>([B)V

    invoke-virtual {v1, v5}, Ljava/security/cert/CertificateFactory;->generateCertificate(Ljava/io/InputStream;)Ljava/security/cert/Certificate;

    move-result-object v1

    check-cast v1, Ljava/security/cert/X509Certificate;
    :try_end_0
    .catch Ljava/security/cert/CertificateException; {:try_start_0 .. :try_end_0} :catch_0

    .line 218
    nop

    .line 223
    :cond_2
    nop

    .line 225
    iget-object p1, p1, Landroid/system/keystore2/KeyEntryResponse;->metadata:Landroid/system/keystore2/KeyMetadata;

    iget-object p1, p1, Landroid/system/keystore2/KeyMetadata;->certificateChain:[B

    .line 227
    if-eqz p1, :cond_4

    .line 228
    invoke-static {p1}, Landroid/security/keystore2/AndroidKeyStoreSpi;->toCertificates([B)Ljava/util/Collection;

    move-result-object p1

    .line 229
    invoke-interface {p1}, Ljava/util/Collection;->size()I

    move-result v0

    add-int/2addr v0, v4

    new-array v0, v0, [Ljava/security/cert/Certificate;

    .line 230
    invoke-interface {p1}, Ljava/util/Collection;->iterator()Ljava/util/Iterator;

    move-result-object p1

    .line 231
    nop

    .line 232
    :goto_0
    invoke-interface {p1}, Ljava/util/Iterator;->hasNext()Z

    move-result v2

    if-eqz v2, :cond_3

    .line 233
    add-int/lit8 v2, v4, 0x1

    invoke-interface {p1}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v5

    check-cast v5, Ljava/security/cert/Certificate;

    aput-object v5, v0, v4

    move v4, v2

    goto :goto_0

    .line 235
    :cond_3
    goto :goto_1

    .line 236
    :cond_4
    new-array v0, v4, [Ljava/security/cert/Certificate;

    .line 238
    :goto_1
    aput-object v1, v0, v3

    .line 239
    return-object v0

    .line 221
    :catch_0
    move-exception p1

    .line 222
    return-object v0

    .line 200
    :cond_5
    :goto_2
    return-object v0
.end method
