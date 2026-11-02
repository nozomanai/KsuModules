# 划卡强行停止与自启动对齐

KernelSU 模块。替换 `/system_ext/framework/miui-services.jar`，让国际版的划卡杀进程和自启动拦截与大陆版一致。目标机型小米 15（dada），系统 `OS3.0.307.0.WOCCNXM`，`ro.product.mod_device=dada_xiaomieu_global`。

原文件备份：`D:\Phone\Mi15\backup\miui-services_OS3.0.307.0.WOCCNXM_dada_xiaomieu_global\miui-services.jar`  
SHA256 `41466bd4d48c1464abe6cca91a409543b823803104198e99390e66fa99ba6c4f`

## 目前的功能

桌面划走卡片时，MiuiHome 发出策略 7（`SwipeUpClean`）。`system_server` 里由 `ProcessSceneCleaner.handleSwipeKill` 处理。

- 有前台服务的进程也会被杀。国际版原先直接跳过这类进程，音乐播放因此停不掉。
- 该应用没有留在其它任务里时，`ProcessCleanerBase.killOnce` 使用等级 104，走 `tryToForceStopPackage`（强行停止）。
- 该应用在别的任务里还有界面时，仍按大陆版做普通杀进程（等级 103），不强制停止。调用在 `killAppForHasOtherTask`。
- 系统应用、允许自启的应用、名单 34 里的应用，仍然不强制停止。
- 小部件应用仍走原来的普通杀进程。这是 `tryToForceStopPackage` 里的既有判断。

其它清理策略（一键清理、游戏清理等）仍保持国际版行为，不会强制停止。

安全中心关掉自启动后，国际版仍能通过几条不看这个开关的路径把应用拉起来。这些路径改为大陆版：

- FCM 广播 `com.google.android.c2dm.intent.RECEIVE` 不再直接放行，要过自启动检查。
- `BOOT_COMPLETED` 和 `LOCKED_BOOT_COMPLETED` 不再走国际版特殊广播豁免。
- `JobScheduler` 由系统绑定，调用方是 `system_server`，原来的自启动检查会直接放过。国际版因此不取消任务。现在和大陆版一样，目标应用不允许自启时取消该 Job。
- 闹钟不再追加国际版白名单。
- 首次开机的广播拦截不再因国际版跳过。
- 冻结策略 `PolicyManager.CN_MODEL` 固定为真，选用 `DomesticPolicyManager`。国际版里写死的「不限制 Job、不限制广播、放行全部 C2DM、`com.google.android` 免自启」不再使用。同一个标志上的其它冻结分支也走大陆版。

服务绑定、同步、账户认证原来两边就共用自启动检查，没有改。系统应用、受保护预装、已经在跑的进程，两边仍会在查自启动之前放行。

## 改了什么

只改了 `classes.dex`，用 baksmali/smali 3.0.10 按 API 29 回编，魔数仍是 `dex 039`。`classes2.dex` 和 jar 里其它条目未动。

`ProcessManagerService.isForceStopEnable`：策略为 7 时，不再因为 `miui.os.Build.IS_INTERNATIONAL_BUILD` 直接返回 false。后面的系统应用、自启、名单 34 判断保留。这个字段来自别的框架包，本 jar 里没有直接读 `ro.product.mod_device`。

`ProcessSceneCleaner.handleSwipeKill` 和 `killAppForHasOtherTask`：去掉国际版对前台服务进程的跳过。

自启动相关，仍在 `classes.dex`：

- `BroadcastQueueModernStubImpl.checkApplicationAutoStart`：不再因国际版放行 FCM。
- `BroadcastQueueModernStubImpl.isInternationalSpecialAction`：直接返回 false。
- `BroadcastQueueModernStubImpl.updateBlockBroadcast`：按大陆版读取首次开机广播拦截。
- `JobServiceContextImpl.checkIfCancelJob`：不再因国际版跳过取消。
- `AlarmManagerServiceStubImpl.init`：不追加资源 `0x11030001` 的国际版闹钟白名单。
- `PolicyManager` 静态初始化：`CN_MODEL` 固定为 true，不再读 `ro.miui.region`。

开机脚本 `post-fs-data.sh` 把模块里的 jar 绑定到 `/system_ext/framework/miui-services.jar`。日志在 `/data/adb/system-server.log`。

## 遇到的坑

`/system_ext` 是独立的 erofs 分区，`/system/system_ext` 只是指向它的符号链接。KernelSU 显示模块已挂载，但系统路径上的 jar 仍是原文件，哈希还是上面的备份值。必须在 `post-fs-data.sh` 里对这个文件做 bind。

这台机器上 `system_server` 直接映射该 jar，不读单独的 odex。设备里没有 `/system/bin/odrefresh`，它在 `/apex/com.android.art/bin/odrefresh`。第一版脚本找不到 `/system/bin/odrefresh` 后把绑定卸掉了，所以重启后划卡仍是原版逻辑。绑定要留着，不要因为找不到 odrefresh 而 umount。

jar 被 `system_server` 映射之后，只改磁盘文件不会生效，需要重启 zygote 或整机重启。

进不了系统时，在 Recovery 解密 data 后执行：

```sh
adb shell touch /data/adb/modules/system-server/disable
```

要删掉模块：

```sh
adb shell rm -rf /data/adb/modules/system-server /data/adb/modules_update/system-server
```

## 后续可能会加

- 其它清理策略也按大陆版允许强制停止，而不只是划卡。
- 划卡时忽略「允许自启」和名单 34，让白名单里的普通应用也能被强行停止。
- `killAppForHasOtherTask` 这条路径目前只做普通杀进程。如果划走后子进程或播放进程还在，再考虑这里也用等级 104。
- 开机日志目前只记绑定结果。需要对照一次划卡时，看 logcat 里的 `ProcessSceneCleaner` 和 `ProcessCleanerBase`。
