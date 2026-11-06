# MIUI 本地化

KernelSU 模块。挂载国行桌面搜索、小爱同学、天气和日历。天气和日历始终覆盖系统同路径应用。桌面搜索和小爱同学仅在未安装时挂载。日志在 `/data/adb/miui-localization.log`。

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
