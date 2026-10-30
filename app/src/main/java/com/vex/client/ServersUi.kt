package com.vex.client

import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * صفحه‌ی سرورها (نیتیو) — دو تب:
 *  ۱) «سرورهای من»: کانفیگ‌های خود کاربر — افزودن با «+»، ویرایش، حذف، پینگ
 *     • تیک/چک‌باکس نیست — انتخاب با «نگه‌داشتن ۲ ثانیه‌ای» روی کارت سرور انجام می‌شود؛
 *       در حالت انتخاب، لمس = افزودن/حذف از انتخاب (چند سرور هم‌زمان قابل انتخاب‌اند)؛
 *       از نوار پایین می‌توان همه‌ی انتخاب‌شده‌ها را یک‌جا حذف کرد یا برایشان پینگ گرفت.
 *     • حذف هیچ دیالوگ تأییدی ندارد — همان لحظه حذف می‌شود؛ فقط اگر سرور در حال
 *       اتصال باشد، برنامه اجازه نمی‌دهد (اول اتصال را قطع کن).
 *  ۲) «سرورهای رایگان»: به‌صورت خودکار از منابع اشتراک عمومی گرفته می‌شوند؛
 *     فقط سرورهای سالم (پینگ‌دار) نشان داده می‌شوند؛ نام همه «Vex Free Server» است؛
 *     کاربر نمی‌تواند ویرایش/حذف کند یا مشخصات سرور را ببیند — فقط لمس = اتصال، یا پینگ.
 */
class ServersUi(private val act: MainActivity) {

    lateinit var root: FrameLayout
        private set

    /** مدت نگه‌داشتن برای انتخاب سرور */
    private val HOLD_MS = 2000L

    /** تب فعلی: 0 = سرورهای من، 1 = سرورهای رایگان */
    var tab: Int = 0
        private set

    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var timerBtn: FrameLayout
    private lateinit var timerIc: IconView
    private lateinit var tabMine: TextView
    private lateinit var tabFree: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var freeProgress: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var cancelSelBtn: FrameLayout
    private lateinit var cancelSelIc: IconView
    private lateinit var pingSelBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var fab: FrameLayout

    private val checked = mutableSetOf<Int>()
    private var p: Palette = Themes.dark()

    fun build(): View {
        val ctx = act
        p = Themes.resolve(ctx, act.store.settings.theme)

        root = FrameLayout(ctx)

        // ---- ستون اصلی (همه‌چیز داخل یک اسکرول — با هدر هم‌راه حرکت می‌کند)
        content = UI.col(ctx)
        content.setPadding(UI.dp(ctx, 18), UI.dp(ctx, 8), UI.dp(ctx, 18), UI.dp(ctx, 110))

        // ---- نوار بالا: عنوان + پینگ همه
        val top = UI.row(ctx)
        title = UI.label(ctx, "سرورها", 19f, p.text, bold = true)
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        top.addView(title)

        // دکمه‌ی زمان‌سنج — پینگ همه‌ی سرورهای تب فعلی
        timerBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11), UI.dp(ctx, 11))
            setOnClickListener {
                if (tab == 0) act.pingAll()
                else act.pingFreeAll()
            }
        }
        timerIc = IconView(ctx, "timer").apply { bold = true }
        timerBtn.addView(timerIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 22), UI.dp(ctx, 22), Gravity.CENTER))
        top.addView(timerBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 46), UI.dp(ctx, 46)).apply { marginStart = UI.dp(ctx, 8) })
        content.addView(top)

        // ---- تب‌ها: سرورهای من | سرورهای رایگان
        val tabsRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 8), 0, 0) }
        tabMine = tabPill("سرورهای من")
        tabFree = tabPill("سرورهای رایگان")
        tabsRow.addView(tabMine, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabsRow.addView(tabFree, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = UI.dp(ctx, 8)
        })
        content.addView(tabsRow)

        val hint = UI.label(ctx, "", 11.5f, p.muted).apply { tag = "hint" }
        hint.setPadding(0, UI.dp(ctx, 6), 0, UI.dp(ctx, 8))
        content.addView(hint)

        // ---- لیست (مستقیم داخل ستون؛ کل صفحه با هم اسکرول می‌شود)
        listContainer = UI.col(ctx)
        content.addView(listContainer)

        emptyHint = UI.label(ctx, "", 14f, p.muted)
        emptyHint.gravity = Gravity.CENTER
        emptyHint.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 50), UI.dp(ctx, 20), 0)

        freeProgress = UI.label(ctx, "", 13.5f, p.accent, bold = true)
        freeProgress.gravity = Gravity.CENTER
        freeProgress.setPadding(UI.dp(ctx, 20), UI.dp(ctx, 70), UI.dp(ctx, 20), 0)
        freeProgress.visibility = View.GONE

        // ---- نوار انتخاب پایین (فقط تب «سرورهای من» وقتی سروری انتخاب شده باشد)
        //      ردیف ۱: تعداد انتخاب + ✕ پایان انتخاب — ردیف ۲: پینگ | حذف
        bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(UI.dp(ctx, 14), UI.dp(ctx, 10), UI.dp(ctx, 14), UI.dp(ctx, 12))
            visibility = View.GONE
            elevation = UI.dp(ctx, 14).toFloat()
        }

        val selRow = UI.row(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        selCount = UI.label(ctx, "۱ سرور انتخاب شد", 13.5f, p.accent, bold = true)
        selCount.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        selRow.addView(selCount)
        cancelSelBtn = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 7), UI.dp(ctx, 7), UI.dp(ctx, 7), UI.dp(ctx, 7))
            setOnClickListener { checked.clear(); syncSelectionMode(); render() }
        }
        cancelSelIc = IconView(ctx, "plus").apply { bold = true; rotation = 45f }
        cancelSelBtn.addView(cancelSelIc, FrameLayout.LayoutParams(
            UI.dp(ctx, 16), UI.dp(ctx, 16), Gravity.CENTER))
        selRow.addView(cancelSelBtn, LinearLayout.LayoutParams(
            UI.dp(ctx, 32), UI.dp(ctx, 32)))
        bottomBar.addView(selRow)

        val selBtns = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 8), 0, 0) }
        pingSelBtn = UI.softButton(ctx, "پینگ انتخاب‌شده‌ها", p)
        pingSelBtn.setOnClickListener {
            if (checked.isEmpty()) UI.toast(ctx, "چیزی برای پینگ انتخاب نشده")
            else act.pingServers(checked.toList())
        }
        selBtns.addView(pingSelBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        deleteBtn = UI.softButton(ctx, "حذف", p)
        deleteBtn.setTextColor(p.bad)
        deleteBtn.setOnClickListener {
            if (checked.isEmpty()) UI.toast(ctx, "چیزی برای حذف انتخاب نشده")
            else act.deleteServers(checked.toSet()) // بدون تأیید — همان لحظه حذف
        }
        selBtns.addView(deleteBtn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = UI.dp(ctx, 8) })
        bottomBar.addView(selBtns)

        // ---- کل صفحه (هدر + عنوان + تب‌ها + لیست) داخل یک اسکرول واحد
        val outerScroll = ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
        }
        outerScroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(outerScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- نوار انتخاب به‌صورت ثابت پایین صفحه (بیرون از اسکرول)
        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            setMargins(UI.dp(ctx, 14), 0, UI.dp(ctx, 14), UI.dp(ctx, 12))
        })

        // ---- دکمه‌ی شناور «+» — پایین سمت راست (فقط تب سرورهای من)
        fab = buildFab()
        root.addView(fab, FrameLayout.LayoutParams(
            UI.dp(ctx, 58), UI.dp(ctx, 58),
            Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = UI.dp(ctx, 18)
            bottomMargin = UI.dp(ctx, 18)
        })

        applyTheme(p)
        return root
    }

    /** چیپ تب بالای لیست */
    private fun tabPill(text: String): TextView {
        val ctx = act
        val t = TextView(ctx).apply {
            this.text = text
            textSize = 13.5f
            typeface = Fonts.bold(ctx)
            gravity = Gravity.CENTER
            setPadding(UI.dp(ctx, 12), UI.dp(ctx, 10), UI.dp(ctx, 12), UI.dp(ctx, 10))
            setOnClickListener { switchTab(if (text == "سرورهای من") 0 else 1) }
        }
        return t
    }

    fun switchTab(idx: Int) {
        tab = idx
        checked.clear()
        render()
        // اگر تب رایگان خالی است، همان لحظه از سرور مدیریت می‌گیریم (نه به‌روزرسانی دوره‌ای)
        if (idx == 1 && act.store.freeServers.isEmpty() && !FreeServers.fetching) {
            act.refreshFreeServers()
        }
    }

    /** دکمه‌ی شناور گرد طلایی با «+» — با جهش نرم هنگام لمس */
    private fun buildFab(): FrameLayout {
        val ctx = act
        val f = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15), UI.dp(ctx, 15))
            elevation = UI.dp(ctx, 8).toFloat()
            contentDescription = "افزودن سرور"
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(0xFFF0C64B.toInt(), 0xFFB9861B.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                setStroke(UI.dp(ctx, 1), UI.alpha(0xFFF7DE8A.toInt(), 0.7f))
            }
            setOnClickListener {
                // جهش کوچک بازخورد لمسی
                ObjectAnimator.ofFloat(it, "scaleX", 1f, 0.9f, 1f).setDuration(180).start()
                ObjectAnimator.ofFloat(it, "scaleY", 1f, 0.9f, 1f).setDuration(180).start()
                act.showAddMenu()
            }
        }
        val ic = IconView(ctx, "plus").apply {
            iconColor = 0xFF1A1304.toInt(); bold = true
        }
        f.addView(ic, FrameLayout.LayoutParams(
            UI.dp(ctx, 28), UI.dp(ctx, 28), Gravity.CENTER))
        return f
    }

    /** وقتی سروری انتخاب شد: نوار انتخاب می‌آید و دکمه‌ی + پنهان می‌شود */
    private fun syncSelectionMode() {
        val selecting = tab == 0 && checked.isNotEmpty()
        bottomBar.visibility = if (selecting) View.VISIBLE else View.GONE
        fab.visibility = if (tab == 0 && !selecting) View.VISIBLE else View.GONE
        if (selecting) selCount.text = "${checked.size} سرور انتخاب شد"
    }

    fun applyTheme(np: Palette) {
        p = np
        val ctx = act
        title.setTextColor(p.text)
        // دکمه‌ی گرد طلایی — هم‌سبک دکمه‌های صفحه‌ی خانه
        timerBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        timerIc.iconColor = p.accent
        // نوار انتخاب
        bottomBar.background = UI.card(ctx, p, 20, stroke = p.accent, fillColor = p.bg2, strokeW = 1.5f)
        selCount.setTextColor(p.accent)
        cancelSelBtn.background = UI.card(ctx, p, 15, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        cancelSelIc.iconColor = p.muted
        pingSelBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        deleteBtn.background = UI.card(ctx, p, fillColor = p.card2, stroke = p.line)
        deleteBtn.setTextColor(p.bad)
        render()
    }

    // ---------------------------------------------------------- رندر لیست

    fun render() {
        listContainer.removeAllViews()
        val ctx = act
        syncSelectionMode()
        if (tab == 0) renderMine() else renderFree()

        // تمپ چیپ‌های تب
        val activeBg = UI.card(ctx, p, 20, fillColor = UI.alpha(p.accent, 0.2f), stroke = p.accent)
        val idleBg = UI.card(ctx, p, 20, fillColor = p.card2, stroke = p.line)
        tabMine.background = if (tab == 0) activeBg else idleBg
        tabFree.background = if (tab == 1) activeBg else idleBg
        tabMine.setTextColor(if (tab == 0) p.accent else p.text)
        tabFree.setTextColor(if (tab == 1) p.accent else p.text)

        // متن راهنمای هر تب
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v is TextView && v.tag == "hint") {
                v.text = if (tab == 0) {
                    if (checked.isEmpty())
                        "لمس = اتصال · نگه‌داشتن ۲ ثانیه = انتخاب برای حذف/پینگ"
                    else
                        "حالت انتخاب: لمس = افزودن/حذف از انتخاب"
                } else
                    "لمس = اتصال · لیست هر دقیقه خودکار به‌روزرسانی می‌شود"
            }
        }
    }

    private fun renderMine() {
        val ctx = act
        title.text = "سرورها (${act.store.servers.size})"
        val servers = act.store.servers
        if (servers.isEmpty()) {
            emptyHint.text = "هنوز سروری نداری.\nبا دکمه‌ی طلایی + سرور خودت را اضافه کن\n(لینک کانفیگ، QR، گالری یا فایل)."
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
            freeProgress.visibility = View.GONE
        } else {
            emptyHint.visibility = View.GONE
            freeProgress.visibility = View.GONE
            for (s in servers) listContainer.addView(serverRow(s))
        }
    }

    private fun renderFree() {
        val ctx = act
        val free = act.store.freeServers
        title.text = "سرورها (${free.size} رایگان)"
        // در تب رایگان: بدون ویرایش/حذف/افزودن
        emptyHint.visibility = View.GONE
        if (FreeServers.fetching) {
            freeProgress.text = freeProgressMsg
            if (freeProgress.parent == null) listContainer.addView(freeProgress)
            freeProgress.visibility = View.VISIBLE
            return
        }
        freeProgress.visibility = View.GONE
        if (free.isEmpty()) {
            emptyHint.text = "هنوز سرور رایگانی از سمت مدیریت اضافه نشده است.\nبه‌محض افزودن در ربات، خودکار همین‌جا ظاهر می‌شود."
            if (emptyHint.parent == null) listContainer.addView(emptyHint)
            emptyHint.visibility = View.VISIBLE
        } else {
            for (s in free) listContainer.addView(freeRow(s))
        }
    }

    /** آخرین پیام پیشرفت دریافت سرورهای رایگان (از MainActivity پست می‌شود) */
    var freeProgressMsg: String = "در حال دریافت…"
        set(value) { field = value; if (::freeProgress.isInitialized) freeProgress.text = value }

    // ---------------------------------------------------------- انتخاب با نگه‌داشتن

    /** افزودن/حذف یک سرور از انتخاب */
    private fun toggleSelect(s: Server) {
        if (s.id in checked) checked.remove(s.id) else checked.add(s.id)
        render()
    }

    /**
     * لمس کارت سرور:
     *  • نگه‌داشتن ۲ ثانیه (با نوار پیشرفت طلایی زیر کارت) → انتخاب/لغو انتخاب
     *  • لمس کوتاه → اگر در حالت انتخابیم: افزودن/حذف از انتخاب؛ وگرنه: اتصال
     *  (اسکرول کردن، نگه‌داشتن را لغو می‌کند)
     */
    private fun attachTouch(card: View, bar: View, s: Server) {
        val h = Handler(Looper.getMainLooper())
        val slop = ViewConfiguration.get(act).scaledTouchSlop
        var downX = 0f; var downY = 0f; var downAt = 0L; var fired = false
        var anim: ValueAnimator? = null

        fun stopAnim() {
            anim?.cancel(); anim = null
            bar.animate().cancel()
            bar.scaleX = 0f
            bar.alpha = 0f
        }

        val holdRunnable = Runnable {
            fired = true
            stopAnim()
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleSelect(s)
        }

        fun cancelHold() {
            h.removeCallbacks(holdRunnable)
            stopAnim()
        }

        card.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    downAt = SystemClock.uptimeMillis()
                    fired = false
                    h.postDelayed(holdRunnable, HOLD_MS)
                    anim = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = HOLD_MS
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            bar.pivotX = bar.width.toFloat() // از راست پر می‌شود (RTL)
                            bar.alpha = 1f
                            bar.scaleX = it.animatedValue as Float
                        }
                    }
                    anim?.start()
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                        cancelHold()
                        v.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelHold()
                    v.isPressed = false
                    val dt = SystemClock.uptimeMillis() - downAt
                    if (!fired && dt < HOLD_MS) {
                        // لمس کوتاه
                        if (checked.isNotEmpty()) toggleSelect(s) else act.selectServer(s)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelHold()
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------- ردیف سرور کاربر

    /** دکمه‌ی گرد کوچک با آیکون، برای ردیف عملیات هر سرور */
    private fun circAction(icon: String, tint: Int, desc: String, onClick: () -> Unit): View {
        val ctx = act
        val b = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
            contentDescription = desc
            setOnClickListener { onClick() }
        }
        // مداد و سطل آشغال: آیکون وکتور (SVG) واقعی با رنگ‌آمیزی تم
        if (icon == "pencil" || icon == "trash") {
            val iv = ImageView(ctx).apply {
                setImageResource(if (icon == "pencil") R.drawable.ic_pencil else R.drawable.ic_trash)
                setColorFilter(tint)
            }
            b.addView(iv, FrameLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
        } else {
            val ic = IconView(ctx, icon).apply { iconColor = tint; bold = true }
            b.addView(ic, FrameLayout.LayoutParams(UI.dp(ctx, 19), UI.dp(ctx, 19), Gravity.CENTER))
        }
        b.background = UI.card(ctx, p, 17, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        return b
    }

    private fun serverRow(s: Server): View {
        val ctx = act
        val selected = act.store.selectedId == s.id
        val isChecked = s.id in checked
        val card = UI.row(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 12), UI.dp(ctx, 12), UI.dp(ctx, 12))
        card.background = UI.card(ctx, p, 16,
            stroke = if (selected || isChecked) p.accent else p.line,
            strokeW = if (isChecked) 2f else 1.5f,
            fillColor = when {
                isChecked -> UI.alpha(p.accent, 0.14f)
                selected -> UI.alpha(p.accent, 0.08f)
                else -> p.card
            })

        val top = UI.row(ctx)

        val col = UI.col(ctx)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val name = UI.label(ctx, s.name, 15f, p.text, bold = true)
        name.maxLines = 1
        col.addView(name)

        val subRow = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 3), 0, 0) }
        val badge = UI.pill(ctx, UI.protoLabel(s.protocol),
            UI.alpha(p.accent, 0.16f), p.accent)
        subRow.addView(badge)
        val inf = try { LinkParser.info(s.raw) } catch (_: Throwable) { null }
        val hp = inf?.host?.let { h -> inf.port?.let { po -> "$h:$po" } ?: h } ?: "—"
        val addr = UI.label(ctx, hp, 12f, p.muted)
        addr.textDirection = View.TEXT_DIRECTION_LTR
        addr.setPadding(UI.dp(ctx, 8), 0, 0, 0)
        addr.maxLines = 1
        subRow.addView(addr, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(subRow)

        top.addView(col)

        val ping = UI.pill(ctx, UI.pingText(s.ping),
            UI.alpha(UI.pingColor(p, s.ping), 0.18f), UI.pingColor(p, s.ping))
        top.addView(ping, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = UI.dp(ctx, 8)
        })

        card.addView(top)

        // ---- ردیف عملیات: پینگ (زمان‌سنج) / ویرایش (مداد) / منو (سه‌نقطه) / حذف (سطل) + نشان وضعیت
        val actions = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 10), 0, 0) }

        val statusTag = UI.label(ctx, when {
            isChecked -> "✓ انتخاب شد"
            selected -> "● فعال"
            else -> ""
        }, 11f, if (isChecked) p.good else p.accent, bold = true)
        statusTag.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        actions.addView(statusTag)

        fun actionLp(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(UI.dp(ctx, 35), UI.dp(ctx, 35)).apply { marginStart = UI.dp(ctx, 6) }

        // پینگ همین سرور
        actions.addView(circAction("timer", p.accent, "پینگ سرور") {
            act.pingServers(listOf(s.id))
        }, actionLp())
        // ویرایش اطلاعات سرور
        actions.addView(circAction("pencil", p.text, "ویرایش سرور") {
            act.showEditServer(s)
        }, actionLp())
        // منوی کامل سرور (تغییر نام، اشتراک‌گذاری، متن کانفیگ…)
        actions.addView(circAction("dots", p.muted, "گزینه‌های سرور") {
            act.showServerMenu(s)
        }, actionLp())
        // حذف سرور — بدون تأیید؛ اگر در حال اتصال به همین سرور باشیم جلوی حذف گرفته می‌شود
        actions.addView(circAction("trash", p.bad, "حذف سرور") {
            act.deleteServers(setOf(s.id))
        }, actionLp())

        card.addView(actions)

        // ---- نوار پیشرفت نگه‌داشتن (زیر کارت، از راست پر می‌شود)
        val bar = View(ctx)
        bar.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = UI.dp(ctx, 2).toFloat()
            setColor(p.accent)
        }
        bar.scaleX = 0f
        bar.alpha = 0f
        card.addView(bar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, UI.dp(ctx, 3)).apply {
            topMargin = UI.dp(ctx, 9)
        })

        // لمس: نگه‌داشتن ۲ ثانیه = انتخاب · لمس کوتاه = اتصال (یا انتخاب در حالت انتخاب)
        attachTouch(card, bar, s)

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = UI.dp(ctx, 10)
        card.layoutParams = lp
        return card
    }

    // ---------------------------------------------------------- ردیف سرور رایگان

    /**
     * ردیف ساده‌ی سرور رایگان:
     *  فقط نام یکسان «Vex Free Server N» + نشان پروتکل + پینگ + دکمه‌ی پینگ تکی.
     *  بدون آدرس/مشخصات، بدون ویرایش/حذف، بدون انتخاب چندگانه.
     */
    private fun freeRow(s: Server): View {
        val ctx = act
        val selected = act.store.selectedId == s.id
        val card = UI.row(ctx)
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(UI.dp(ctx, 14), UI.dp(ctx, 11), UI.dp(ctx, 12), UI.dp(ctx, 11))
        card.background = UI.card(ctx, p, 16,
            stroke = if (selected) p.accent else p.line,
            fillColor = if (selected) UI.alpha(p.accent, 0.08f) else p.card)

        // نشان پروتکل
        val badge = UI.pill(ctx, UI.protoLabel(s.protocol),
            UI.alpha(p.accent, 0.16f), p.accent)
        card.addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = UI.dp(ctx, 10)
        })

        // نام + پینگ (ستون وسط)
        val col = UI.col(ctx)
        col.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val name = UI.label(ctx, s.name, 14.5f, p.text, bold = true)
        name.maxLines = 1
        col.addView(name)
        val pingLine = UI.row(ctx).apply { setPadding(0, UI.dp(ctx, 3), 0, 0) }
        val ping = UI.label(ctx, UI.pingText(s.ping), 12f, UI.pingColor(p, s.ping), bold = true).apply {
            textDirection = View.TEXT_DIRECTION_LTR
        }
        pingLine.addView(ping)
        val active = UI.label(ctx, if (selected) "  ·  ● فعال" else "", 11f, p.accent, bold = true)
        pingLine.addView(active)
        col.addView(pingLine)
        card.addView(col)

        // دکمه‌ی پینگ تکی
        val pb = FrameLayout(ctx).apply {
            setPadding(UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8), UI.dp(ctx, 8))
            contentDescription = "پینگ سرور رایگان"
            background = UI.card(ctx, p, 17, stroke = p.line, fillColor = p.card2, strokeW = 1.5f)
        }
        val pi = IconView(ctx, "timer").apply { iconColor = p.accent; bold = true }
        pb.addView(pi, FrameLayout.LayoutParams(UI.dp(ctx, 17), UI.dp(ctx, 17), Gravity.CENTER))
        pb.setOnClickListener { act.pingServers(listOf(s.id)) }
        card.addView(pb, LinearLayout.LayoutParams(
            UI.dp(ctx, 33), UI.dp(ctx, 33)).apply { marginStart = UI.dp(ctx, 8) })

        // لمس = انتخاب و اتصال — هیچ منوی دیگری وجود ندارد
        card.setOnClickListener { act.selectServer(s) }

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = UI.dp(ctx, 10)
        card.layoutParams = lp
        return card
    }

    fun clearChecks() = checked.clear()
}
