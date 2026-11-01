package ir.gplus.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val resumed = mutableStateOf(false)
    private val homeSignal = mutableIntStateOf(0)
    private val reloadKey = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = "com.android.providers.tv.permission.READ_EPG_DATA"
        if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(p), 1)
        }
        setContent { LauncherApp(resumed.value, homeSignal.intValue, reloadKey.intValue) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeSignal.intValue++ // دکمه Home: برگشت به صفحه‌ی اصلی
    }

    override fun onResume() { super.onResume(); resumed.value = true }
    override fun onPause() { resumed.value = false; super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reloadKey.intValue++ // بعد از دادن اجازه، لیست کانال‌ها دوباره خوانده شود
    }
}
