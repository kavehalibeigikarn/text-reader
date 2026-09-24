package ir.kaveh.screenreader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Speaker.init(this)
        CloudTts.init(this)
    }
}
