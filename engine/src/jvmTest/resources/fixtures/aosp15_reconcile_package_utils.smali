.class public abstract Lcom/android/server/pm/ReconcilePackageUtils;
.super Ljava/lang/Object;
.source "ReconcilePackageUtils.java"


# static fields
.field public static final ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS:Z


# direct methods
.method static constructor <clinit>()V
    .registers 1

    .line 61
    sget-boolean v0, Landroid/os/Build;->IS_DEBUGGABLE:Z

    .line 62
    sput-boolean v0, Lcom/android/server/pm/ReconcilePackageUtils;->ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS:Z

    return-void
.end method

.method public static isCompatSignatureUpdateNeeded(Lcom/android/server/pm/Settings$VersionInfo;)Z
    .registers 2

    .line 346
    iget p0, p0, Lcom/android/server/pm/Settings$VersionInfo;->databaseVersion:I

    const/4 v0, 0x2

    if-ge p0, v0, :cond_0

    const/4 p0, 0x1

    goto :goto_0

    :cond_0
    const/4 p0, 0x0

    :goto_0
    return p0
.end method
