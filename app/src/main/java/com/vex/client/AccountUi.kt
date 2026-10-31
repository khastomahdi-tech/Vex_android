package com.vex.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * تب «حساب کاربری» — مشخصات اشتراکِ همین دستگاه:
 *  وضعیت (فعال / منقضی / حذف‌شده)، زمان باقی‌مانده، کد اشتراک (کپی‌شدنی)،
 *  تاریخ فعال‌سازی و انقضا (شمسی)، نام دستگاه و تعداد نشست‌های فعال با همین کد.
 *
 *  وضعیت از سرور (demonvpn.shop) تازه می‌شود؛ اگر مدیر از ربات «حذف تمام نشست‌ها»
 *  را زده باشد، اینجا هم «حذف شده» دیده می‌شود و برنامه به صفحه‌ی ورود کد برمی‌گردد.
 */
class AccountUi(private val act: MainActivity) {

    lateinit var root: LinearLayout
        private set

    private var p: Palette = Themes.royal()

    private lateinit var badge: TextView
    private lateinit var remainBig: TextView
    private lateinit var remainLbl: TextView
    private lateinit var codeVal: TextView
    private lateinit var createdVal: TextView
    private lateinit var expiresVal: TextView
    private lateinit var deviceVal: TextView
    private lateinit var sessionsVal: TextView
    private lateinit var refreshBtn: TextView
    private lateinit var hintTv: TextView

    /** اختلاف ساعت سرور با گوشی — برای محاسبه‌ی دقیق زمان باقی‌مانده */
    private var serverDelta = 0L
    private var last: TelegramGate.SubInfo? = null

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = UI.col(ctx)
        root.setPadding(UI.dp(ctx, 16), UI.dp(ctx, 10), UI.dp(ctx, 16), UI.dp(ctx, 14))
        val scroll = ScrollView(ctx).apply { overScrollMode = View.OVER_SCROLL_NEVER }

        // ================= وضعیت اشتراک =================
        val hero = UI.col(ctx).apply {
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 18), UI.dp(ctx, 16), UI.dp(ctx, 18))
        }
        badge = UI.pill(ctx, "—", UI.alpha(p.accent, 0.16f), p.accent).apply { textSize = 13f }
        hero.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        remainBig = TextView(ctx).apply {
            textSize = 26f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 14), 0, 0)
        }
        hero.addView(remainBig)
        remainLbl = UI.label(ctx, "زمان باقی‌مانده‌ی اشتراک", 12.5f, p.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, UI.dp(ctx, 4), 0, 0)
        }
        hero.addView(remainLbl)
        addCard(hero)

        // ================= مشخصات =================
        val info = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        info.addView(sectionLabel(ctx, "مشخصات حساب"))

        codeVal = infoRow(info, "کد اشتراک", "—", copyable = true)
        createdVal = infoRow(info, "تاریخ فعال‌سازی", "—")
        expiresVal = infoRow(info, "تاریخ انقضا", "—")
        deviceVal = infoRow(info, "دستگاه", "—")
        sessionsVal = infoRow(info, "دستگاه‌های فعال با این کد", "—")
        addCard(info)

        // ================= بررسی وضعیت =================
        val actCard = UI.col(ctx).apply {
            setPadding(UI.dp(ctx, 16), UI.dp(ctx, 14), UI.dp(ctx, 16), UI.dp(ctx, 16))
        }
        actCard.addView(sectionLabel(ctx, "بررسی وضعیت"))
        refreshBtn = UI.softButton(ctx, "🔄 بروزرسانی وضعیت از سرور", p).apply {
            setPadding(UI.dp(ctx, 18), UI.dp(ctx, 12), UI.dp(ctx, 18), UI.dp(ctx, 12))
        }
        refreshBtn.setOnClickListener {
            UI.toast(act, "در حال بررسی…")
            refreshAsync(showResult = true)
        }
        actCard.addView(refreshBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 10), 0, 0)
        })
        hintTv = UI.label(ctx,
            "کد اشتراک ۲۷ حرفیِ انگلیسی را از ربات تلگرام می‌گیری؛ هر کد فقط روی یک دستگاه فعال می‌شود. برای کد جدید، تمدید یا حذف نشست‌ها به همان ربات مراجعه کن.",
            12.5f, p.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(UI.dp(ctx, 2).toFloat(), 1f)
            setPadding(0, UI.dp(ctx, 12), 0, 0)
        }
        actCard.addView(hintTv)
        val botBtn = UI.softButton(ctx, "🤖 ورود به ربات", p).apply {
            setPadding(UI.dp(ctx, 18), UI.dp(ctx, 12), UI.dp(ctx, 18), UI.dp(ctx, 12))
        }
        botBtnRef = botBtn
        botBtn.setOnClickListener { act.openVexBot() }
        actCard.addView(botBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, UI.dp(ctx, 10), 0, 0)
        })
        addCard(actCard)

        scroll.addView(root, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.isFillViewport = true
        applyTheme(p)
        return scroll
    }

    /** یک ردیف «برچسب / مقدار» در کارت مشخصات — خروجی: TextView مقدار */
    private fun infoRow(parent: LinearLayout, lbl: String, initial: String, copyable: Boolean = false): TextView {
        val ctx = act
        val r = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 9), 0, UI.dp(ctx, 9)) }
        val l = UI.label(ctx, lbl, 13f, p.muted)
        val v = UI.label(ctx, initial, 13.5f, p.text, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        if (copyable) {
            v.setTextIsSelectable(false)
            v.setOnClickListener { copyCode() }
        }
        r.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(v, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 10)
        })
        parent.addView(r)
        if (copyable) {
            val note = UI.label(ctx, "روی کد بزن تا کپی شود", 11f, p.muted)
            note.setPadding(0, 0, 0, UI.dp(ctx, 2))
            parent.addView(note)
        }
        return v
    }

    private fun copyCode() {
        val c = SubStore.code(act)
        if (c.isEmpty()) return
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("vex_code", c))
            UI.toast(act, "کد کپی شد")
        } catch (_: Throwable) {}
    }

    private fun addCard(view: View) {
        val ctx = act
        val wrap = LinearLayout(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }
        view.background = UI.card(ctx, p)
        wrap.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(wrap)
    }

    private fun sectionLabel(ctx: Context, text: String): TextView =
        UI.label(ctx, text, 16f, p.accent, bold = true)

    // ---------------------------------------------------------- رندر

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        for (i in 0 until root.childCount) {
            val wrap = root.getChildAt(i)
            if (wrap is LinearLayout && wrap.childCount == 1) {
                wrap.getChildAt(0).background = UI.card(ctx, p)
            }
        }
        refreshBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        refreshBtn.setTextColor(p.text)
        botBtnBg()
        render()
    }

    private var botBtnRef: TextView? = null
    private fun botBtnBg() {
        botBtnRef?.let {
            it.background = UI.card(act, p, fillColor = p.card2, stroke = p.line)
            it.setTextColor(p.text)
        }
    }

    /** ورود به تب — رندر + دریافت تازه‌ی وضعیت از سرور */
    fun onOpen() {
        render()
        refreshAsync(showResult = false)
    }

    fun render() {
        val ctx = act
        val code = SubStore.code(ctx)
        val now = System.currentTimeMillis() + serverDelta
        val exp = SubStore.expires(ctx)
        val info = last
        val kicked = info != null && info.kicked
        val expired = exp in 1 until now

        when {
            !SubStore.valid(code) -> {
                badge.text = "اشتراک فعال نیست"
                badge.setTextColor(p.mid)
                remainBig.text = "—"
                remainBig.setTextColor(p.muted)
            }
            kicked -> {
                badge.text = "حذف شده توسط مدیریت"
                badge.setTextColor(p.bad)
                remainBig.text = "بیرون انداخته شدی"
                remainBig.setTextColor(p.bad)
            }
            expired -> {
                badge.text = "منقضی شده"
                badge.setTextColor(p.bad)
                remainBig.text = "۰ روز"
                remainBig.setTextColor(p.bad)
            }
            else -> {
                badge.text = "اشتراک فعال است ✓"
                badge.setTextColor(p.good)
                remainBig.text = faDigits(remainingText(exp - now))
                remainBig.setTextColor(p.good)
            }
        }

        codeVal.text = if (SubStore.valid(code)) codeFmt(code) else "—"
        createdVal.text = faDigits(jalali(SubStore.activatedAt(ctx)))
        expiresVal.text = faDigits(jalali(exp))
        deviceVal.text = SubStore.device(ctx).ifEmpty { "—" }
        sessionsVal.text = if (info != null && info.ok) faDigits(info.sessions.toString()) + " دستگاه" else "—"
    }

    /** دریافت تازه‌ی وضعیت از سرور — اگر اشتراک حذف/منقضی شده باشد، برنامه قفل می‌شود */
    fun refreshAsync(showResult: Boolean) {
        Thread {
            val code = SubStore.code(act)
            if (!SubStore.valid(code)) {
                if (showResult) act.mainHandler().post { UI.toast(act, "هنوز اشتراک فعالی نداری") }
                return@Thread
            }
            val info = TelegramGate.subStatus(code, TelegramGate.deviceId(act))
            act.mainHandler().post {
                if (info.ok) {
                    serverDelta = info.serverTime - System.currentTimeMillis()
                    last = info
                    if (info.expires > 0) {
                        SubStore.save(act, info.code, info.createdAt, info.expires,
                            info.device.ifEmpty { SubStore.device(act) })
                    }
                    if (showResult) UI.toast(act, "اشتراک فعال است ✓")
                } else if (info.kicked || info.expired || info.notFound) {
                    SubStore.clear(act)
                    last = null
                    if (showResult || act.currentTab == 4) {
                        UI.toast(act, if (info.kicked) "نشست شما حذف شد" else "کد اشتراک شما منقضی شده است")
                    }
                    act.onSubscriptionLost(
                        if (info.kicked) "نشست شما توسط مدیریت حذف شده است. کد اشتراک را دوباره وارد کن."
                        else "کد اشتراک شما منقضی شده است. برای گرفتن کد جدید، وارد ربات شو."
                    )
                    return@post
                } else {
                    if (showResult) UI.toast(act, "دسترسی به سرور مدیریت نبود")
                }
                render()
            }
        }.start()
    }

    // ---------------------------------------------------------- ابزار

    /** زمان باقی‌مانده به شکل خوانا: «۹ روز و ۵ ساعت» */
    private fun remainingText(ms: Long): String {
        if (ms <= 0) return "۰ روز"
        val d = ms / 86400000L
        val h = (ms % 86400000L) / 3600000L
        val m = (ms % 3600000L) / 60000L
        return when {
            d > 0 -> "${d} روز و ${h} ساعت"
            h > 0 -> "${h} ساعت و ${m} دقیقه"
            else -> "${m} دقیقه"
        }
    }

    /** ۱۲۳۴-۵۶۷۸-۹۰۱۲-۳۴۵۶ یا XXXXXXXXX-XXXXXXXXX-XXXXXXXXX */
    private fun codeFmt(c: String): String =
        if (c.any { it in 'A'..'Z' }) c.replace(Regex("([A-Z]{9})(?=[A-Z])"), "$1-")
        else c.replace(Regex("(\\d{4})(?=\\d)"), "$1-")

    /** ارقام فارسی */
    private fun faDigits(s: String): String {
        val f = "۰۱۲۳۴۵۶۷۸۹"
        return buildString { for (c in s) append(if (c in '0'..'9') f[c - '0'] else c) }
    }

    /** تاریخ شمسی (اندروید ۷+) با بازگشت به میلادی روی نسخه‌های قدیمی‌تر */
    private fun jalali(ms: Long): String {
        if (ms <= 0) return "—"
        return try {
            if (Build.VERSION.SDK_INT >= 24) {
                val cal = android.icu.util.Calendar.getInstance(
                    android.icu.util.ULocale("fa_IR@calendar=persian"))
                cal.timeInMillis = ms
                val y = cal.get(android.icu.util.Calendar.YEAR)
                val m = cal.get(android.icu.util.Calendar.MONTH) + 1
                val d = cal.get(android.icu.util.Calendar.DAY_OF_MONTH)
                String.format(java.util.Locale.US, "%d/%02d/%02d", y, m, d)
            } else {
                gregorian(ms)
            }
        } catch (_: Throwable) {
            gregorian(ms)
        }
    }

    private fun gregorian(ms: Long): String = try {
        val f = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US)
        f.format(java.util.Date(ms))
    } catch (_: Throwable) { "—" }
}
