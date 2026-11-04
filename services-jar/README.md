# services-jar

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

开机脚本 `post-fs-data.sh` 把模块里的文件绑定到：

- `/system_ext/framework/miui-services.jar`
- `/system/framework/services.jar`
- `/odm/lib64/libqc_hal.so`

`/system`、`/system_ext`、`/odm` 都是 erofs，KernelSU 的普通挂载替换不了这三个文件。`mfp-daemon` 属于 `late_start`，绑定发生在它启动之前。脚本若发现它已经在跑，会 `stop` 再 `start`，让它重新映射改过的库。模块里的 `libqc_hal.so` 会被标成 `system_file`，`vendor_mfp-daemon` 读不到，指纹 HAL 就会把 `persist.vendor.sys.fp.vendor` 写成 `none`，设置里的指纹解锁也会消失。脚本在绑定之后把这个库改成 `vendor_file`。日志在 `/data/adb/services-jar.log`。

## 人脸解锁清掉指纹失败次数

失败次数在高通超声波指纹 HAL 的用户态计数里，不在 SystemUI，也不在 `miui-services.jar`。这台机器 `persist.vendor.qfp.hal_enable_lockout` 默认是 0，`mTaLockoutHandling` 是 0，计数不在 TA 里。指纹比对成功时 HAL 自己清零。人脸 sensor 8 的强度是 4095（convenience），`BiometricService.resetLockoutTimeBound` 要求强度至少 15，所以人脸成功不能走那条正式接口。

人脸认证成功时，`FaceAuthenticationClient.onAuthenticated` 对每个指纹传感器调用 `FingerprintManager.resetLockout`，令牌是 69 字节的 0。传入 null 时 `FingerprintResetLockoutClient` 不调用 HAL。69 字节的 0 会编成一个字段全空的 `HardwareAuthToken`，HAL 才会收到这次 reset。异常只打日志，不打断人脸解锁。

`libqc_hal.so` 的 `QfpService::resetLockout` 原来会验硬件令牌，验失败就不清次数；`hal_enable_lockout` 为 0 时还会在清零前直接返回。现在这三处都改成继续走原来的清零：

- `0x5f998` 的 `cbz` 改成无条件跳到校验成功后的清零。
- `0x5f7ec` 的 `cbnz` 改成 `nop`，V2 校验失败也清。
- `0x5f798` 的提前返回改成 `nop`。

密码解开走另一条路。`BiometricDeferredQueue.processPendingLockoutsForFingerprint` 在传感器既要硬件令牌、又要 challenge 时，原来只打 `No fingerprint HAL interface requires HAT with challenge`，不调用 reset。现在这种传感器也向 Gatekeeper 要令牌再 `resetLockout`。不要令牌的传感器仍用空令牌，和原来一样。

`services.jar` 的 smali 在 `D:\Phone\Mi15\modified\services`。只改了 `classes.dex` 和 `classes2.dex`，用 baksmali/smali 3.0.10 按 API 29 回编，魔数仍是 `dex 039`。原文件 SHA256 `3247e260f765dce05f5324e5106727314d22f691043643fd8b1fd15a136dc092`。模块内 `services.jar` SHA256 `131a03bf0319159f930e10b728d29f7e994dc6624fa5aaf12bebcaef0819e3a1`。

原 `libqc_hal.so` SHA256 `4017b7d98119da8502c68f739e7d2e8df94dcb447c7de6a00d772f1eb62999ff`。模块内 SHA256 `282d6745de4763443f977ffc6c6f8e1966393ab5b7c517f41f4499b07070a103`。

## 遇到的坑

`/system_ext` 是独立的 erofs 分区，`/system/system_ext` 只是指向它的符号链接。`/system` 和 `/odm` 同样是 erofs。KernelSU 显示模块已挂载，但这些路径上的文件仍是原文件。必须在 `post-fs-data.sh` 里逐个 bind，不能在某一个已经绑上时提前退出。

这台机器上 `system_server` 直接映射该 jar，不读单独的 odex。设备里没有 `/system/bin/odrefresh`，它在 `/apex/com.android.art/bin/odrefresh`。第一版脚本找不到 `/system/bin/odrefresh` 后把绑定卸掉了，所以重启后划卡仍是原版逻辑。绑定要留着，不要因为找不到 odrefresh 而 umount。

jar 被 `system_server` 映射之后，只改磁盘文件不会生效，需要重启 zygote 或整机重启。

进不了系统时，在 Recovery 解密 data 后执行：

```sh
adb shell touch /data/adb/modules/services-jar/disable
```

要删掉模块：

```sh
adb shell rm -rf /data/adb/modules/services-jar /data/adb/modules_update/services-jar
```

## 后续可能会加

- 其它清理策略也按大陆版允许强制停止，而不只是划卡。
- 划卡时忽略「允许自启」和名单 34，让白名单里的普通应用也能被强行停止。
- `killAppForHasOtherTask` 这条路径目前只做普通杀进程。如果划走后子进程或播放进程还在，再考虑这里也用等级 104。
- 开机日志目前只记绑定结果。需要对照一次划卡时，看 logcat 里的 `ProcessSceneCleaner` 和 `ProcessCleanerBase`。
