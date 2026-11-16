package io.autumn.systemui.xposed.statusbar

/**
 * 下拉栏音量、亮度长按会进入各自的二级面板。
 * 两个滑条都先问 supportLongClick，返回 false 时不会打开面板，拖动调节不受影响。
 */
object SliderDetail {
    const val BRIGHTNESS = "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"
    const val VOLUME = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"

    @Volatile
    var enabled: Boolean = false
}
