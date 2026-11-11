# 指纹升频封顶

按下指纹（解锁或录入）时，系统会申请一把大约 1 秒的性能锁，把大核频率顶到 4GHz 以上。这个模块把这次升频的上限改掉：小核停在 1996800Hz，大核停在 2438400Hz。频率表上没有正好的 2000MHz 和 2400MHz，这两档是各自最接近的一档。

日志写在 `/data/adb/fod-freq-cap.log`。

## 为什么会到 4GHz

指纹进程 `mfp-daemon` 在手指按下时调用 `libqc_hal.so` 里的 `QfpPerfLock::setPerfLock()`。这把锁的资源表把小核和大核的最低频、最高频都写成 `0xFFF`（4095）。

性能服务收到之后，把这个数乘以 1000，再向上对齐到 CPU 频率表里的下一档：

| 请求 | 乘 1000 之后 | 落到的频率 |
|---|---|---|
| `0xFFF`（4095），小核 cpu0–5 | 4095000 | 3532800，已经超过该簇最高频，所以用最高档 |
| `0xFFF`（4095），大核 cpu6–7 | 4095000 | 4204800 |
| 1996 | 1996000 | 1996800 |
| 2438 | 2438000 | 2438400 |

同一时刻温控还会把大核上限放宽到 4089600。性能锁要的 4204800 比这个更高，实际跑出来就是大约 4.09GHz。

另外两处也有同样的 `0xFFF` 表，模块一并改掉：

- `fingerprint.qcom_us.so` 里的 `xiaomi_perf_lock_acquire()`
- `vendor.xiaomi.hw.touchfeature-service` 在手指按下时持有的约 200ms 性能锁

内核 `metis` 的 `mi_fboost_enable` 会在开机时写成 0。指纹这条 4GHz 路径不依赖它，它是另一条会把频率抬高的开关。

## 实现

`post-fs-data.sh` 和 `service.sh` 都调用 `apply.sh`。脚本先进入 init 的挂载命名空间，再对三个文件做同样的事：

1. 从 `/odm` 复制原文件到模块目录。
2. 核对资源表开头的固定字节。对不上就停止，避免系统更新后改错位置。
3. 把表里的 `0xFFF` 写成 1996 或 2438。单位是 MHz，性能服务会自己乘 1000。
4. 给副本标上原来的 SELinux 上下文，再 bind 回原路径。`libqc_hal.so` 和指纹库用 `vendor_file`，touchfeature 用它自己的执行上下文。
5. 重启已经在跑的 `mfp-daemon` 和 touchfeature。进程启动时映射的是旧文件，只挂载不够，要等新进程重新打开。

`0xFFF` 在三张表里的位置：

| 文件 | 表的文件偏移 | 改哪些值 |
|---|---|---|
| `/odm/lib64/libqc_hal.so` | `0x3f250` | 小核最低/最高 1996，大核最低/最高 2438 |
| `/odm/lib64/hw/fingerprint.qcom_us.so` | `0x1c124` | 同上 |
| `/odm/bin/hw/vendor.xiaomi.hw.touchfeature-service` | `0x383b0` | 四个簇的最低频和最高频，小核侧 1996，大核侧 2438 |

`sepolicy.rule` 允许 init 在重启 touchfeature 时切换到它的域。卸载时卸掉这三处 bind，并重启指纹服务。
