package dev.hcsummaries.export

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Privacy policy shown from the app and by Health Connect when the user asks why the app
 * needs access (ACTION_SHOW_PERMISSIONS_RATIONALE on Android 13-, VIEW_PERMISSION_USAGE on 14+).
 * Static text only; it does not read any data.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(TextView(context).apply {
                text = getString(R.string.privacy_title)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text))
            })
            addView(TextView(context).apply {
                text = getString(R.string.privacy_body)
                textSize = 15f
                setLineSpacing(0f, 1.2f)
                setTextColor(getColor(R.color.text))
                setPadding(0, dp(12), 0, dp(12))
            })
            addView(Button(context).apply {
                text = getString(R.string.privacy_close)
                isAllCaps = false
                setTextColor(getColor(R.color.brand_on))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(getColor(R.color.brand))
                }
                setOnClickListener { finish() }
            })
        }
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)
    }
}
