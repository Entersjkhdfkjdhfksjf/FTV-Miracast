package dev.wfdsink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny dependency-free HTTP server. Every request needs ?k=<key>; the key is
 * shown on the TV by HomeActivity. Only the launching actions below exist.
 */
object WebServer {
    private const val TAG = "WFDWEB"
    const val PORT = 8080

    @Volatile private var started = false
    private val pool = Executors.newCachedThreadPool()
    private lateinit var app: Context
    var key: String = ""
        private set

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        val prefs = app.getSharedPreferences("web", Context.MODE_PRIVATE)
        var k = prefs.getString("key", null)
        if (k == null) {
            k = java.lang.Long.toHexString(java.security.SecureRandom().nextLong() and 0xffffffffL).padStart(8, '0')
            prefs.edit().putString("key", k).apply()
        }
        key = k
        pool.execute {
            try {
                val ss = ServerSocket(PORT)
                while (true) {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                }
            } catch (e: Exception) {
                started = false
                android.util.Log.w(TAG, "server stopped", e)
            }
        }
    }

    fun ips(): List<String> {
        val out = ArrayList<String>()
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (!a.isLoopbackAddress && a is Inet4Address) out.add(a.hostAddress ?: "")
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun launchableApps(): List<Pair<String, String>> {
        val pm = app.packageManager
        val seen = HashSet<String>()
        val res = ArrayList<Pair<String, String>>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER")) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val pkg = ri.activityInfo.packageName
                if (seen.add(pkg)) res.add(Pair(ri.loadLabel(pm).toString(), pkg))
            }
        }
        return res.sortedBy { it.first.lowercase() }
    }

    fun launch(pkg: String): Boolean {
        val pm = app.packageManager
        val li = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(li)
        return true
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            val inp = s.getInputStream().bufferedReader()
            val line = inp.readLine() ?: return
            while (true) {
                val h = inp.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = line.split(" ")
            if (parts.size < 2) return
            val target = parts[1]
            val path = target.substringBefore('?')
            val q = parseQuery(target.substringAfter('?', ""))
            val out = s.getOutputStream()
            if (path == "/favicon.ico") { respond(out, 204, "text/plain", ""); return }
            if (q["k"] != key) {
                respond(out, 401, "text/html", "<h3>Key required. The URL with the key is shown on the TV screen.</h3>")
                return
            }
            if (path == "/") respond(out, 200, "text/html; charset=utf-8", PAGE)
            else if (path.startsWith("/api/")) respond(out, 200, "application/json", api(path, q).toString())
            else respond(out, 404, "text/plain", "not found")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "request failed", e)
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    private fun parseQuery(qs: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (p in qs.split('&')) {
            val i = p.indexOf('=')
            if (i > 0) m[URLDecoder.decode(p.substring(0, i), "UTF-8")] = URLDecoder.decode(p.substring(i + 1), "UTF-8")
        }
        return m
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
        out.flush()
    }

    private fun api(path: String, q: Map<String, String>): JSONObject {
        val r = JSONObject()
        try {
            val pm = app.packageManager
            if (path == "/api/apps") {
                val arr = JSONArray()
                for (a in launchableApps()) arr.put(JSONObject().put("label", a.first).put("pkg", a.second))
                r.put("apps", arr)
            } else if (path == "/api/launch") {
                val pkg = q["pkg"] ?: ""
                if (launch(pkg)) r.put("ok", true) else r.put("error", "no launch intent for " + pkg)
            } else if (path == "/api/find") {
                // Lists exported activities whose class name matches a regex, so we can discover
                // the stock mirroring screen without hardcoding Amazon's internal names.
                val re = Regex(q["q"] ?: "mirror|wfd|display|cast", RegexOption.IGNORE_CASE)
                val arr = JSONArray()
                for (pi in pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                    val acts = pi.activities ?: continue
                    for (a in acts) {
                        if (arr.length() >= 200) break
                        if (a.exported && re.containsMatchIn(a.name)) {
                            arr.put(JSONObject().put("pkg", pi.packageName).put("cls", a.name))
                        }
                    }
                }
                r.put("activities", arr)
            } else if (path == "/api/start") {
                val i = Intent()
                i.component = ComponentName(q["pkg"] ?: "", q["cls"] ?: "")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(i)
                r.put("ok", true)
            } else if (path == "/api/intent") {
                val act = q["action"] ?: ""
                if (!act.startsWith("android.settings.")) {
                    r.put("error", "only android.settings.* actions are allowed")
                } else {
                    app.startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    r.put("ok", true)
                }
            } else if (path == "/api/home") {
                app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                r.put("ok", true)
            } else {
                r.put("error", "unknown endpoint")
            }
        } catch (e: Exception) {
            r.put("error", e.toString())
        }
        return r
    }

    private val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Fire TV control</title>
<style>body{font-family:sans-serif;margin:16px;max-width:720px}button{margin:3px;padding:8px 12px}input{padding:8px;width:60%}pre{background:#eee;padding:8px;overflow:auto}</style>
<h2>Fire TV control</h2>
<div><button onclick="intent('android.settings.CAST_SETTINGS')">Cast settings</button>
<button onclick="intent('android.settings.WIFI_DISPLAY_SETTINGS')">Wi-Fi display settings</button>
<button onclick="intent('android.settings.SETTINGS')">Settings</button>
<button onclick="call('home')">Our home screen</button></div>
<h3>Find screens</h3><input id=q value="mirror|wfd|display|cast"> <button onclick="find()">Search</button><div id=found></div>
<h3>Apps</h3><div id=apps></div><pre id=log></pre>
<script>
var K=new URLSearchParams(location.search).get('k')||'';
function api(p,params){var u=new URLSearchParams(params||{});u.set('k',K);return fetch('/api/'+p+'?'+u.toString()).then(function(r){return r.json()});}
function log(x){var l=document.getElementById('log');l.textContent=JSON.stringify(x)+'\n'+l.textContent;}
function call(p,params){api(p,params).then(log);}
function intent(a){call('intent',{action:a});}
function btn(t,f){var b=document.createElement('button');b.textContent=t;b.onclick=f;return b;}
function find(){api('find',{q:document.getElementById('q').value}).then(function(d){var c=document.getElementById('found');c.innerHTML='';(d.activities||[]).forEach(function(a){c.appendChild(btn(a.pkg+' / '+a.cls.split('.').pop(),function(){call('start',{pkg:a.pkg,cls:a.cls});}));c.appendChild(document.createElement('br'));});log({found:(d.activities||[]).length});});}
api('apps').then(function(d){var c=document.getElementById('apps');(d.apps||[]).forEach(function(a){c.appendChild(btn(a.label,function(){call('launch',{pkg:a.pkg});}));});});
</script>"""
}
