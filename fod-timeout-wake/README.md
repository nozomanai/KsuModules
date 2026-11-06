# 熄屏指纹超时亮屏

KernelSU 模块。替换 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`，让熄屏按住指纹时，0.3 秒内没有解开就亮起锁屏。目标机型小米 15（dada），系统 `OS3.0.307.0.WOCCNXM`。SystemUI 版本 `16.03.251211.r`。

模块内 APK SHA256 `558301abbaeb444f9f9da2449ab556ef98ee1c8dcd7df2ab6ec26fc77723f43d`

## 目前的功能

屏幕处于息屏（`mDozing`）时，手指按在屏下指纹上会开始计时。

- 0.3 秒内指纹已经解开：沿用系统原来的解锁流程，本模块不再额外亮屏。
- 到 0.3 秒仍未解开，并且屏幕还不是亮屏：调用 `PowerManager.wakeUp()`，亮起锁屏。唤醒原因是 `WAKE_REASON_BIOMETRIC`（16），详情字符串是 `com.android.systemui:FOD_TIMEOUT`。
- 屏幕已经亮着时按指纹：不挂这个计时，亮屏解锁行为保持原样。

再次按下会把上一次还没到点的计时清掉，从这次按下重新算 0.3 秒。0.3 秒内抬起手指会取消这次计时，屏幕不会因此亮起。一直按住超过 0.3 秒且还没解开，到点仍会亮屏。

## 改了什么

只改了 `classes3.dex`，用 baksmali/smali 3.0.10 按 API 29 回编，魔数仍是 `dex 039`。`classes.dex`、`classes2.dex`、`classes4.dex`、`resources.arsc` 和 `AndroidManifest.xml` 未动。

`com.miui.keyguard.biometrics.fod.MiuiGxzwIconView`：

- `onTouchDown()` 在 `turnOnAodIfScreenOff()` 之后调用新增的 `scheduleWakeIfNotUnlocked()`。已经处于按下状态时的提前返回没有改，同一次按住不会重复计时。
- 新增 `scheduleWakeIfNotUnlocked()`。只有 `mDozing` 为真才向 `mHandler` 投递 300 毫秒延时任务，日志标签 `MiuiGxzwViewIcon`，内容 `scheduleWakeIfNotUnlocked 300ms`。
- `onTouchUp()` 一开始调用 `cancelWakeIfNotUnlocked()`，对同一个 `mHandler` 执行 `removeCallbacks`。没有挂过计时，或任务对象还是空时直接返回。
- 新增字段 `mWakeIfNotUnlocked`，保存这个任务，供抬手取消和下一次按下重计时。

新增 `com.miui.keyguard.biometrics.fod.MiuiGxzwIconView$WakeIfNotUnlocked`，到点后依次判断：

- 仍在息屏。
- `IFingerPrintManager.isUnlockByGxzw()` 为假。识别成功时系统会把 `MiuiGxzwManager.mGxzwUnlockMode` 设成 1 或 2，这个判断就会为真，从而不再亮屏。
- `mPowerManager` 不为空，且 `isInteractive()` 为假。
- 通过后打日志 `wakeScreen fod timeout`，再 `wakeUp`。

指纹识别本身、熄屏时把屏幕拉到 DOZE 画指纹图标、以及识别成功后的解锁，都还是原来的 `MiuiGxzw` 路径。

## 开机脚本

`post-fs-data.sh` 只在 `ro.build.version.incremental` 等于 `OS3.0.307.0.WOCCNXM` 时生效。`/system_ext` 是独立的 erofs，KernelSU 的普通挂载替换不了这个 APK，所以脚本把模块里的 APK 绑定到 `/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk`。

绑定之后删掉 SystemUI 的 dalvik 缓存和 profile，让系统按改过的 `classes3.dex` 重新编译。第一次开机因此会慢一些。日志在 `/data/adb/fod-timeout-wake.log`。

进不了系统时，在 Recovery 解密 data 后执行：

```sh
adb shell touch /data/adb/modules/fod-timeout-wake/disable
```

要删掉模块：

```sh
adb shell rm -rf /data/adb/modules/fod-timeout-wake /data/adb/modules_update/fod-timeout-wake
```
