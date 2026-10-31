package com.mediatech.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceManager

/**
 * מסך הגדרות בשלושה עיצובים (נבחר בהגדרות: "עיצוב מסך ההגדרות"):
 * cards = כרטיסים (ברירת מחדל), chips = בחירות מהירות, tiles = קטגוריות.
 * כולם קוראים וכותבים לאותן הגדרות.
 */
class SettingsActivity : AppCompatActivity() {

    private sealed class Def(val key: String, val title: String)
    private class ListDef(key: String, title: String, val def: String, val entries: Int, val values: Int) : Def(key, title)
    private class SwitchDef(key: String, title: String, val def: Boolean) : Def(key, title)
    private class ActionDef(key: String, title: String) : Def(key, title)
    private class Section(val title: String, val icon: Int, val items: List<Def>, val advanced: List<Def> = emptyList())

    private val BG = 0xFF121212.toInt()
    private val CARD = 0xFF1E1E1E.toInt()
    private val DIV = 0xFF2C2C2C.toInt()
    private val ACC = 0xFFF19A22.toInt()
    private val MUTED = 0xFFABABAB.toInt()
    private val TXT = 0xFFF2F2F2.toInt()
    private val DARK = 0xFF1A1A1A.toInt()

    private val sp by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var openSection = -1
    private var advancedOpen = false
    private var scroll: ScrollView? = null

    private val sections: List<Section> by lazy {
        listOf(
            Section("אחסון", R.drawable.ic_set_folder, listOf(
                ActionDef("save_location", "מיקום שמירה"),
                ActionDef("reset_location", "חזרה למיקום ברירת מחדל")
            )),
            Section("צילום", R.drawable.ic_set_camera, listOf(
                ListDef("timer", "טיימר עצמי", "0", R.array.timer_entries, R.array.timer_values),
                ListDef("aspect", "גודל תמונה", "full", R.array.aspect_entries, R.array.aspect_values),
                ListDef("photo_quality", "איכות צילום", "high", R.array.pq_entries, R.array.pq_values),
                ListDef("wb", "איזון לבן", "auto", R.array.wb_entries, R.array.wb_values)
            ), listOf(
                ListDef("scene", "מצב סצנה", "off", R.array.scene_entries, R.array.scene_values),
                ListDef("antibanding", "אנטי-פליקר", "auto", R.array.ab_entries, R.array.ab_values),
                SwitchDef("nr", "הפחתת רעש (איכות גבוהה)", false)
            )),
            Section("כפתורים", R.drawable.ic_set_buttons, listOf(
                ListDef("vol_action", "פעולת כפתורי עוצמת קול", "shutter", R.array.va_entries, R.array.va_values)
            )),
            Section("וידאו", R.drawable.ic_set_video, listOf(
                ListDef("video_quality", "איכות וידאו", "FHD", R.array.vq_entries, R.array.vq_values),
                SwitchDef("eis", "ייצוב תמונה (EIS)", false),
                SwitchDef("mic", "הקלטת קול", true)
            )),
            Section("תצוגה", R.drawable.ic_set_display, listOf(
                ListDef("settings_style", "עיצוב מסך ההגדרות", "cards", R.array.ss_entries, R.array.ss_values),
                SwitchDef("auto_rotate", "סיבוב אוטומטי", true)
            )),
            Section("אודות", R.drawable.ic_set_info, listOf(ActionDef("report_bug", "האפליקציה בפיתוח")))
        )
    }

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                sp.edit().putString("tree", uri.toString()).apply()
                toast("מיקום השמירה עודכן")
                render()
            }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun style() = sp.getString("settings_style", "cards") ?: "cards"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        render()
    }

    override fun onSupportNavigateUp(): Boolean { goBack(); return true }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (style() == "tiles" && openSection >= 0) { openSection = -1; render() } else finish()
    }

    // ------------------------------------------------------------------ בניית המסך
    private fun render() {
        val y = scroll?.scrollY ?: 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when (style()) {
            "chips" -> buildChips(root)
            "tiles" -> if (openSection in sections.indices) buildSection(root, sections[openSection]) else buildTiles(root)
            else -> buildCards(root)
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(root)
        }
        scroll = sv
        setContentView(sv)
        sv.post { sv.scrollTo(0, y) }
        supportActionBar?.title =
            if (style() == "tiles" && openSection in sections.indices) sections[openSection].title
            else getString(R.string.settings_title)
    }

    // א · כרטיסים
    private fun buildCards(root: LinearLayout) {
        for (s in sections) {
            if (s.title == "אודות") continue
            root.addView(label(s.title))
            root.addView(card(s.items), cardLp(0))
        }
        addAdvanced(root)
        root.addView(footer())
    }

    // ב · בחירות מהירות
    private fun buildChips(root: LinearLayout) {
        val segs = mutableListOf<ListDef>()
        val rest = mutableListOf<Def>()
        for (s in sections) {
            if (s.title == "אודות") continue
            for (d in s.items) {
                if (d is ListDef && resources.getStringArray(d.entries).size <= 4) segs += d else rest += d
            }
        }
        for (d in segs) {
            root.addView(TextView(this).apply {
                text = d.title; setTextColor(0xFFD8D8D8.toInt()); textSize = 13f
                setPadding(dp(20), dp(12), dp(20), dp(6))
            })
            root.addView(segControl(d), cardLp(0).apply { topMargin = 0 })
        }
        root.addView(card(rest), cardLp(16))
        addAdvanced(root)
        root.addView(footer())
    }

    // ג · קטגוריות
    private fun buildTiles(root: LinearLayout) {
        val order = listOf("צילום", "וידאו", "אחסון", "כפתורים", "תצוגה", "אודות")
        val list = order.mapNotNull { t -> sections.firstOrNull { it.title == t } }
        for (pair in list.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (s in pair) row.addView(tile(s), LinearLayout.LayoutParams(0, dp(150), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(150), 1f))
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), 0) })
        }
        root.addView(footer())
    }

    private fun buildSection(root: LinearLayout, s: Section) {
        root.addView(card(s.items), cardLp(12))
        if (s.advanced.isNotEmpty()) {
            root.addView(label("מתקדם"))
            root.addView(card(s.advanced), cardLp(0))
        }
    }

    private fun addAdvanced(root: LinearLayout) {
        val adv = sections.flatMap { it.advanced }
        if (adv.isEmpty()) return
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 16) }
        head.addView(makeRow("מתקדם", if (advancedOpen) "⌃" else "‹", null) { advancedOpen = !advancedOpen; render() })
        root.addView(head, cardLp(16))
        if (advancedOpen) root.addView(card(adv), cardLp(8))
    }

    // ------------------------------------------------------------------ רכיבים
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun cardLp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(top), dp(12), 0) }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(ACC); textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(20), dp(14), dp(20), dp(6))
    }

    private fun footer() = TextView(this).apply {
        text = "האפליקציה בפיתוח · ezerphone0@gmail.com"
        setTextColor(0xFF9A9A9A.toInt()); textSize = 11f
        setPadding(dp(20), dp(16), dp(20), dp(28))
        setOnClickListener { copyEmail() }
    }

    private fun card(defs: List<Def>): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(CARD, 16) }
        for ((i, d) in defs.withIndex()) {
            if (i > 0) c.addView(View(this).apply { setBackgroundColor(DIV) }, LinearLayout.LayoutParams(-1, 1))
            c.addView(rowFor(d))
        }
        return c
    }

    private fun rowFor(d: Def): View = when (d) {
        is SwitchDef -> {
            val sw = SwitchCompat(this).apply {
                isChecked = sp.getBoolean(d.key, d.def)
                setOnCheckedChangeListener { _, c -> sp.edit().putBoolean(d.key, c).apply() }
            }
            makeRow(d.title, null, sw) { sw.toggle() }
        }
        is ListDef -> makeRow(d.title, listSummary(d) + "  ‹", null) { showList(d) }
        is ActionDef -> makeRow(d.title, actionSummary(d), null) { runAction(d) }
    }

    private fun makeRow(title: String, value: String?, trailing: View?, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            val tvv = TypedValue()
            if (this@SettingsActivity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvv, true))
                setBackgroundResource(tvv.resourceId)
            setOnClickListener { onClick() }
        }
        r.addView(TextView(this).apply { text = title; setTextColor(TXT); textSize = 15f }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!value.isNullOrEmpty()) r.addView(TextView(this).apply {
            text = value; setTextColor(MUTED); textSize = 13f; gravity = Gravity.END
        })
        if (trailing != null) r.addView(trailing)
        return r
    }

    private fun segControl(d: ListDef): View {
        val ev = resources.getStringArray(d.entries)
        val vv = resources.getStringArray(d.values)
        val cur = sp.getString(d.key, d.def) ?: d.def
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(CARD, 12)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        for (i in ev.indices) {
            val sel = vv[i] == cur
            box.addView(TextView(this).apply {
                text = ev[i]; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(if (sel) DARK else 0xFFCFCFCF.toInt())
                if (sel) { setTypeface(typeface, Typeface.BOLD); background = round(ACC, 10) }
                setOnClickListener { if (!sel) setList(d, vv[i]) }
            }, LinearLayout.LayoutParams(0, dp(36), 1f))
        }
        return box
    }

    private fun tile(s: Section): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { openSection = sections.indexOf(s); render() }
        }
        val box = FrameLayout(this).apply { background = round(0xFF2A2218.toInt(), 14) }
        box.addView(ImageView(this).apply { setImageResource(s.icon) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        t.addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))
        t.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        t.addView(TextView(this).apply {
            text = s.title; setTextColor(TXT); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
        })
        t.addView(TextView(this).apply { text = sectionSummary(s); setTextColor(MUTED); textSize = 12f })
        return t
    }

    // ------------------------------------------------------------------ ערכים ופעולות
    private fun listSummary(d: ListDef): String {
        val cur = sp.getString(d.key, d.def) ?: d.def
        val i = resources.getStringArray(d.values).indexOf(cur)
        return if (i >= 0) resources.getStringArray(d.entries)[i] else cur
    }

    private fun summaryByKey(key: String): String {
        val d = sections.flatMap { it.items + it.advanced }.filterIsInstance<ListDef>().first { it.key == key }
        return listSummary(d)
    }

    private fun sectionSummary(s: Section): String = when (s.title) {
        "צילום" -> summaryByKey("aspect") + " · " + summaryByKey("photo_quality")
        "וידאו" -> summaryByKey("video_quality") + " · " + (if (sp.getBoolean("mic", true)) "עם קול" else "בלי קול")
        "אחסון" -> if (sp.getString("tree", null) == null) "DCIM/Camera" else "תיקייה מותאמת"
        "כפתורים" -> "עוצמת קול: " + summaryByKey("vol_action")
        "תצוגה" -> if (sp.getBoolean("auto_rotate", true)) "סיבוב אוטומטי" else "ללא סיבוב"
        else -> "האפליקציה בפיתוח"
    }

    private fun actionSummary(d: ActionDef): String = when (d.key) {
        "save_location" -> {
            val t = sp.getString("tree", null)
            if (t == null) "DCIM/Camera ברירת מחדל" else Uri.decode(Uri.parse(t).lastPathSegment ?: "תיקייה מותאמת")
        }
        "report_bug" -> "ezerphone0@gmail.com"
        else -> ""
    }

    private fun showList(d: ListDef) {
        val ev = resources.getStringArray(d.entries)
        val vv = resources.getStringArray(d.values)
        val cur = vv.indexOf(sp.getString(d.key, d.def) ?: d.def)
        AlertDialog.Builder(this)
            .setTitle(d.title)
            .setSingleChoiceItems(ev, cur) { dlg, which -> setList(d, vv[which]); dlg.dismiss() }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun setList(d: ListDef, v: String) {
        sp.edit().putString(d.key, v).apply()
        render()
    }

    private fun runAction(d: ActionDef) {
        when (d.key) {
            "save_location" -> picker.launch(null)
            "reset_location" -> {
                sp.edit().remove("tree").apply()
                toast("הוחזר לברירת מחדל")
                render()
            }
            "report_bug" -> copyEmail()
        }
    }

    private fun copyEmail() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("email", "ezerphone0@gmail.com"))
        if (Build.VERSION.SDK_INT < 33) toast("הכתובת הועתקה")   // באנדרואיד 13+ המערכת מציגה הודעה בעצמה
    }
}
