# 指纹学习开关

KernelSU 模块。在模块列表左侧点执行按钮，开关小米超声波指纹的学习。目标机型小米 15（dada），系统 `OS3.0.307.0.WOCCNXM`。指纹为高通超声波，`persist.vendor.sys.fp.vendor=qcom_us`，模组 OMS。

## 目前的功能

成功解锁后，指纹 HAL 可以把这次采集写回模板。这个过程在 `fingerprint.qcom_us.so` 的 `setStudyConfig` 里，配置名是 `AE_ENABLE`。手指只有微湿才能解开时，写回去的都是湿手指，干手会越来越难。

执行按钮读 `persist.vendor.sys.fp.disable_study`，再写回相反状态：

- 属性为空或 `0`：学习开着。点一次写成 `1`，HAL 下发 `AE_ENABLE=0`，之后成功解锁不再更新模板。
- 属性为其它非 `0` 值：学习关着。点一次写回 `0`，HAL 下发 `AE_ENABLE=1`，成功解锁继续更新模板。

关闭学习不会改已有模板，干手指也不会因此变好。要改善的话，先关掉，再到设置里删掉全部指纹，用干手重新录入，并保持关闭。

结果直接显示在执行按钮的输出里。日志在 `/data/adb/fp-study-toggle.log`，logcat 标签 `FpStudyToggle`。

## 改了什么

只改这一个属性，用 `resetprop -p` 写入。没有 `resetprop` 时退回 `setprop`。

没有替换指纹 HAL、TrustZone 算法包或已录入的模板。`/mnt/vendor/persist/fingerprint/meta/bge_*.dat`、工厂校准 `persist.vendor.sys.fp.calistate` 和 `persist.vendor.sys.fp.norm` 都不动。

属性自己会留在重启之后。卸载模块不会把它改回。下次按下指纹时，正在运行的 `mfp-daemon` 会读到新值。想马上让指纹进程重读，重启一次。
