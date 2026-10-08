
.class public final Landroid/app/ActivityThread;
.super Ljava/lang/Object;

.method private handleBindApplication(Landroid/app/ActivityThread$AppBindData;)V
    .registers 3
    const/4 v0, 0x0
    iput-object p1, p0, Landroid/app/ActivityThread;->mBoundApplication:Landroid/app/ActivityThread$AppBindData;
    invoke-static {p1}, Landroid/security/kaorios/KaoriosHook;->initActivityThread(Ljava/lang/Object;)V
    return-void
.end method
