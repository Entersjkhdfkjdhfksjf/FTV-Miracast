package dev.wfdsink

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Minimal replacement home screen: shows the web-control URL and a list of apps. */
class HomeActivity : Activity() {
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WebServer.start(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(48, 48, 48, 48)
        info = TextView(this)
        info.textSize = 22f
        root.addView(info)

        val apps = WebServer.launchableApps()
        val list = ListView(this)
        list.adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, apps.map { it.first })
        list.setOnItemClickListener { _, _, pos, _ -> WebServer.launch(apps[pos].second) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val ips = WebServer.ips()
        val urls = if (ips.isEmpty()) "no network yet" else ips.joinToString("\n") { "http://" + it + ":" + WebServer.PORT + "/?k=" + WebServer.key }
        info.text = "Open on your phone or PC (same Wi-Fi):\n" + urls
    }

    // Home screens should not exit on Back.
    override fun onBackPressed() { }
}
