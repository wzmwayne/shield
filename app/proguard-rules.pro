# 用户服务类由 Shizuku 在 shell 进程中通过反射实例化，必须保留类名与构造器。
-keep class wzmwayne.shield.shell.ShieldShellService { <init>(); <init>(android.content.Context); }
-keep class wzmwayne.shield.shell.IShieldShell { *; }
-keep class wzmwayne.shield.shell.IShieldShell$Stub { *; }
