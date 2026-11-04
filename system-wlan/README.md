# 系统应用 WLAN

KernelSU 模块。替换手机管家里的联网控制，让应用详情中的 WLAN 开关对系统应用也能关闭。目标机型小米 15（dada），系统 `OS3.0.307.0.WOCCNXM`，`ro.product.mod_device=dada_xiaomieu_global`。手机管家 `com.miui.securitycenter` `12.8.3-260831.0.1`（`versionCode` 40001283），路径 `/product/priv-app/SecurityCenter/SecurityCenter.apk`。

改过的安装包 SHA256 `7b6fec3494a38b87de2a78ccc7c13fa067eac66862fb07e1a5b370031a5d1ac9`。

管家更新后这份安装包不能继续用，需要按新版本重新打。

## 目前的功能

应用详情里的 WLAN 开关原先对系统应用不可点。打开页面时，如果该系统应用的 WLAN 已经被禁止，还会被改回允许。管家加载联网规则时再改回一次。第三方应用不走这两段，所以一直能关。

现在系统应用在详情页可以关闭 WLAN，关闭后的规则会留下。点击后仍走原来的 `setWifiRule`。

下面这些包仍不能关。`setWifiRuleNoCheckSys` 对预设白名单直接返回失败，详情页也会把开关禁用：

- `com.xiaomi.finddevice`
- `com.miui.greenguard`
- `com.miui.mishare.connectivity`
- `com.android.networkstack`、`com.google.android.networkstack` 及其 tethering、permissionconfig
- `com.android.captiveportallogin`、`com.google.android.captiveportallogin`
- `com.google.android.cellbroadcastservice`
- 资源 `config_firewall_white_list` 里追加的包，以及按地区、运营商加进去的锁机客户端

企业管控把某个包标成网络受限时，移动数据、WLAN、后台联网三个开关仍会一起禁用。

## 改了什么

只改了 `classes3.dex` 里的两条指令，没有整包回编。改完后重算了 dex 的 SHA-1 和 Adler32。安装包按原压缩方式重打包，未压缩的 `.so` 仍按 4096 字节对齐。签名块已去掉，平台签名对不上。

`ShowAppDetailFragment.initFirewallData`：系统应用分支原来是 `if-eqz v7, +059h`（`38 07 59 00`，字节码偏移 `0x2bc`）。`v7` 来自 `AppInfo.isSystemApp`，非系统应用才跳过后面的处理。这条改成 `goto/16 +059h`（`29 00 59 00`），始终跳过。跳过之前 WLAN 开关已经 `setToolbarItemEnable(true)`。

被跳过的处理是：除 `com.qti.qcc` 以外的系统应用禁用 WLAN 开关；WLAN 规则不是允许时，再调用 `setWifiRule(Allow)` 并勾回允许。

`FirewallService.setSystemAppWifiRuleAllow`：第一条指令由 `const/4 v0, 0`（`12 00`）改成 `return-void`（`0e 00`）。这个方法从 `loadWifiAndRoamingFirewall` 调用，原先会把系统应用里已经是禁止的 WLAN 规则改回允许。

`ShowAppDetailFragment$7.onToolbarItemClick` 和 `FirewallService$FirewallBinder.setWifiRule` 没有改。系统应用设置 WLAN 规则时，本来就会对同一 uid 下的包调用 `setWifiRuleNoCheckSys`。

`service.sh` 等到 `sys.boot_completed` 之后，把模块里的安装包绑定到 `/product/priv-app/SecurityCenter/SecurityCenter.apk`，再 `am force-stop com.miui.securitycenter`。日志在 `/data/adb/system-wlan.log`。

绑定放在开机完成之后，是因为包管理器扫描系统应用时会验签名。扫到这份改过的安装包时，手机会丢掉手机管家。开机早期扫描到的仍是分区里的原文件。

## 遇到的坑

挂载之前已经在跑的管家主进程仍用内存里的旧代码。`am force-stop` 当时没有把这个主进程换掉，详情页会继续是灰的。重启后脚本会再次挂载并结束管家。若开关仍不可点，再结束一次 `com.miui.securitycenter`。

在 KernelSU 里删除本模块后重启即可恢复。绑定不会留到下一次开机。想在这次开机里立刻恢复原文件：

```sh
umount /product/priv-app/SecurityCenter/SecurityCenter.apk
am force-stop com.miui.securitycenter
```
