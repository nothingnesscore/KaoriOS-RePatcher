.class public Landroid/app/Instrumentation;
.super Ljava/lang/Object;

.method public newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;
    .registers 20
    const/4 v0, 0x0
    invoke-static/range {p2 .. p2}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V

    return-object v0
.end method

.method public newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;
    .registers 20
    const/4 v0, 0x0
    invoke-static/range {p3 .. p3}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V

    return-object v0
.end method
