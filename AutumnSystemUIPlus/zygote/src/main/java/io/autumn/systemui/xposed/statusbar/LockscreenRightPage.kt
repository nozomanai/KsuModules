package io.autumn.systemui.xposed.statusbar

/**
 * 锁屏右滑拉开的是左侧功能页。
 * KeyguardMoveLeftController.supportMoveToRight 为假时，按下、跟随手指和松手都不会进入该页。
 */
object LockscreenRightPage {
    const val CONTROLLER = "com.android.keyguard.negative.KeyguardMoveLeftController"

    @Volatile
    var enabled: Boolean = true
}
