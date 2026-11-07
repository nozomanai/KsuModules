# MIUI 本地化

KernelSU 模块。挂载国行桌面搜索、小爱同学、天气、日历、相册和时钟。天气、日历、相册和时钟始终覆盖系统同路径应用。桌面搜索和小爱同学仅在未安装时挂载。日志在 `/data/adb/miui-localization.log`。

相册和时钟的安装包由小米欧重签过。首次覆盖后的那次开机，模块会从软件包记录里去掉这两个包，让系统按国行签名重新安装。照片还在系统相册目录里。时钟的闹钟存在应用自己的数据里，这次重装会清掉。相册的收藏和自建相册分类也可能要重新整理。

国行相册没有普通的 Java 代码，入口库是 `libapp_gallery.so`。库放在 `MiuiGallery/lib/arm64`，和安装包一起挂到 `/product/priv-app/MiuiGallery`。只换 apk、不带这个目录时，相册会一打开就退出。

## 闹钟没有「法定工作日」

国行时钟的重复选项分两套。`miui.os.Build.IS_INTERNATIONAL_BUILD` 为 true 时用 `alarm_repeat_type_no_workdays`，里面没有法定工作日和法定节假日。这台手机是 Xiaomi.eu，这个值是 true，所以设定闹钟时不能选智能跳过节假日、只在工作日响铃。

模块里的时钟把 `Util.isInternational()` 固定返回 false，改走国内那套 `alarm_repeat_type`。节假日表打在安装包的 `res/raw/holiday.json` 里，包含 2026 年。这次改动换了时钟的签名，开机时会先把时钟数据拷到 `/data/adb/miui-localization-clock-keep`，新包装好后再拷回去。

## 日历左上角不显示月份

国行日历 `com.android.calendar` 的左上角月份是 `action_bar_month_year_view`。它是 `wrap_content` 的文字，只有同时满足下面两点才会被写上「11月」这类文字：

- `READ_CALENDAR` 和 `WRITE_CALENDAR` 都已授予
- 应用自己的偏好 `sp_permission_necessary_dialog` 为 true

这个偏好写在 `/data/user/0/com.android.calendar/shared_prefs/com.android.calendar_preferences.xml`。大陆版第一次打开时，系统权限声明点同意后会把它设为 true。

这台小米 15 是 Xiaomi.eu，`ro.product.mod_device=dada_xiaomieu_global`，框架里的 `miui.os.Build.IS_INTERNATIONAL_BUILD` 为 true。国行日历因此跳过权限声明，偏好一直是默认的 false。月份文字不写，控件宽度缩成 0，左上角是空的。月历格子、农历和节日不走这段判断，所以其余界面正常。

补法：先停掉日历，在上面的偏好文件里加上

```xml
<boolean name="sp_permission_necessary_dialog" value="true" />
```

再打开日历。清除日历数据后这个值会丢，月份会再次消失，按同样方式补回。
