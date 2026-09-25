package io.luftaquila.hometty

/** Separate test APK launcher activities prove that a typed name actually opens an app. */
class LaunchProbeActivity : android.app.Activity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        setContentView(android.widget.TextView(this).apply { text = "앱 실행 확인 / Launch confirmed" })
    }
}
