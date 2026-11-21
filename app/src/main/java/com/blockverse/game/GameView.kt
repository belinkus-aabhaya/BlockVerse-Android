package com.blockverse.game

import android.content.Context
import android.app.Activity
import android.os.Build
import android.util.Log
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import java.util.Calendar
import android.view.HapticFeedbackConstants
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.CountDownTimer
import android.os.SystemClock
import android.os.Bundle
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.Button
import android.widget.TextView
import java.util.UUID

class GameView(context: Context, private val store: LocalStore) : View(context) {
    private val engine = GameEngine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val boldTypeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val bg = Color.rgb(15, 20, 39)
    private val panel = Color.rgb(27, 34, 59)
    private val muted: Int
        get() = if (dark) Color.rgb(151, 161, 190) else Color.rgb(82, 94, 116)
    private val white = Color.WHITE
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var screen = "home"
    private var selected = -1
    private var pendingBlast = ""
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var hintCell: Cell? = null
    private var hintSlot = -1
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var downX = 0f
    private var downY = 0f
    private var gestureCancelled = false
    private var gameRecorded = false
    private var sessionId = ""
    private var hasSavedGame = false
    private var hasActiveGame = false
    private var dark = true
    private var haptics = true
    private var soundEnabled = true
    private var timeRemainingMs = 90_000L
    private var timeAttackDeadline = 0L
    private var timeAttackFinished = false
    private var timer: CountDownTimer? = null
    private val soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private var placeSound = 0
    private var clearSound = 0
    private var comboSound = 0
    private val loadedSounds = mutableSetOf<Int>()
    private var accessibilityFocusedId = View.NO_ID
    private var hoveredAccessibilityId = View.NO_ID
    private val accessibilityManager by lazy { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }

    private data class VirtualNode(val id: Int, val label: String, val bounds: RectF, val clickable: Boolean = true, val selected: Boolean = false)
    private val virtualAccessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) {
                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@GameView)
                    setPackageName(context.packageName)
                    className = GameView::class.java.name
                    contentDescription = this@GameView.contentDescription
                    setBoundsInParent(Rect(0, 0, width, height))
                    val location = IntArray(2)
                    this@GameView.getLocationOnScreen(location)
                    setBoundsInScreen(Rect(location[0], location[1], location[0] + width, location[1] + height))
                    virtualNodes().forEach { addChild(this@GameView, it.id) }
                }
            }
            val spec = virtualNodes().firstOrNull { it.id == virtualViewId } ?: return null
            val bounds = Rect(
                (offsetX + spec.bounds.left * scale).toInt(),
                (offsetY + spec.bounds.top * scale).toInt(),
                (offsetX + spec.bounds.right * scale).toInt(),
                (offsetY + spec.bounds.bottom * scale).toInt()
            )
            return AccessibilityNodeInfo.obtain().apply {
                setSource(this@GameView, spec.id)
                setParent(this@GameView)
                setPackageName(context.packageName)
                className = if (spec.clickable) Button::class.java.name else TextView::class.java.name
                contentDescription = spec.label
                setBoundsInParent(bounds)
                val location = IntArray(2)
                this@GameView.getLocationOnScreen(location)
                setBoundsInScreen(Rect(location[0] + bounds.left, location[1] + bounds.top, location[0] + bounds.right, location[1] + bounds.bottom))
                isVisibleToUser = visibility == View.VISIBLE
                isEnabled = true
                isFocusable = true
                isClickable = spec.clickable
                isSelected = spec.selected
                if (spec.clickable) addAction(AccessibilityNodeInfo.ACTION_CLICK)
                addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                if (accessibilityFocusedId == spec.id) {
                    isAccessibilityFocused = true
                    addAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                }
            }
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualNodes().none { it.id == virtualViewId }) return false
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedId != virtualViewId) {
                    accessibilityFocusedId = virtualViewId
                    sendVirtualAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                }
                return true
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (accessibilityFocusedId == virtualViewId) {
                    accessibilityFocusedId = View.NO_ID
                    sendVirtualAccessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                return true
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val node = virtualNodes().firstOrNull { it.id == virtualViewId } ?: return false
                return node.clickable && activateVirtualNode(virtualViewId)
            }
            return false
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocusedId != View.NO_ID)
                createAccessibilityNodeInfo(accessibilityFocusedId) else null
    }

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        dark = store.setting("theme", "dark") == "dark"
        applySystemBarAppearance()
        haptics = store.setting("haptics", "on") == "on"
        soundEnabled = store.setting("sound", "on") == "on"
        soundPool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loadedSounds.add(sampleId) }
        placeSound = soundPool.load(context, R.raw.block_place, 1)
        clearSound = soundPool.load(context, R.raw.line_clear, 1)
        comboSound = soundPool.load(context, R.raw.combo_burst, 1)
        val saved = store.loadGame()
        val restored = saved != null && engine.importState(saved)
        if (!restored) {
            if (saved != null) store.clearGame()
            engine.start()
        }
        sessionId = store.loadSessionId().orEmpty().ifBlank { UUID.randomUUID().toString() }
        gameRecorded = store.isSessionFinished(sessionId)
        if (engine.mode == "TIME_ATTACK") {
            timeRemainingMs = store.loadTimeRemaining(90_000L)
            timeAttackFinished = timeRemainingMs <= 0L
            if (timeAttackFinished) recordCurrentGame()
        }
        hasActiveGame = restored
        hasSavedGame = restored && !timeAttackFinished
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = "BlockVerse offline block puzzle game. Current screen: home."
    }

    fun applySystemBarAppearance() {
        val activity = context as? Activity ?: return
        val lightBackground = Color.rgb(242, 245, 252)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!dark) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.decorView.systemUiVisibility = flags
        activity.window.statusBarColor = if (dark) bg else lightBackground
        activity.window.navigationBarColor = if (dark || Build.VERSION.SDK_INT < 26) bg else lightBackground
    }

    fun setSystemInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insetLeft = left.coerceAtLeast(0); insetTop = top.coerceAtLeast(0)
        insetRight = right.coerceAtLeast(0); insetBottom = bottom.coerceAtLeast(0)
        invalidate()
    }

    fun persist() {
        if (!hasActiveGame) return
        if (engine.mode == "TIME_ATTACK" && timeAttackDeadline > 0L) timeRemainingMs = (timeAttackDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val saved = runCatching {
            store.saveGame(engine.exportState(), sessionId, if (engine.mode == "TIME_ATTACK") timeRemainingMs else 0L)
        }.onFailure { Log.e("BlockVerse", "Unable to persist the current game", it) }.isSuccess
        hasSavedGame = saved && !timeAttackFinished
    }
    fun pauseTimer() {
        timer?.cancel(); timer = null
        if (timeAttackDeadline > 0L) timeRemainingMs = (timeAttackDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        timeAttackDeadline = 0L
        if (engine.mode == "TIME_ATTACK" && timeRemainingMs <= 0L && !timeAttackFinished) finishTimeAttack()
    }
    fun resumeTimer() { if (screen == "game" && engine.mode == "TIME_ATTACK" && !engine.gameOver && !timeAttackFinished) startTimer() }
    private fun startTimer() {
        if (engine.mode != "TIME_ATTACK" || timeAttackFinished || timeRemainingMs <= 0L || timer != null) return
        timeAttackDeadline = SystemClock.elapsedRealtime() + timeRemainingMs
        timer = object : CountDownTimer(timeRemainingMs, 250L) {
            override fun onTick(millisUntilFinished: Long) { timeRemainingMs = millisUntilFinished; invalidate() }
            override fun onFinish() { finishTimeAttack() }
        }.start()
    }
    private fun recordCurrentGame(): Boolean {
        if (gameRecorded) return true
        return runCatching {
            gameRecorded = store.finishGameOnce(sessionId, engine.score) || store.isSessionFinished(sessionId)
            gameRecorded
        }.onFailure { Log.e("BlockVerse", "Unable to record completed game", it) }.getOrDefault(false)
    }
    private fun finishTimeAttack() {
        if (timeAttackFinished) return
        timer?.cancel(); timer = null; timeAttackDeadline = 0L; timeRemainingMs = 0L; timeAttackFinished = true
        recordCurrentGame()
        persist(); invalidate()
    }
    private fun feedback() { if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    private fun playSound(id: Int) { if (soundEnabled && id != 0 && loadedSounds.contains(id)) soundPool.play(id, 0.55f, 0.55f, 1, 0, 1f) }
    fun dispose() { timer?.cancel(); timer = null; soundPool.release() }
    fun goHome(): Boolean {
        if (screen != "home") {
            pauseTimer()
            if (engine.gameOver || engine.challengeComplete || timeAttackFinished) recordCurrentGame()
            screen = "home"; persist(); invalidate(); return true
        }
        return false
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = virtualAccessibilityProvider

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        virtualNodes().forEach { info.addChild(this, it.id) }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!accessibilityManager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val designX = (event.x - offsetX) / scale
                val designY = (event.y - offsetY) / scale
                val hit = virtualNodes().firstOrNull { it.bounds.contains(designX, designY) }?.id ?: View.NO_ID
                if (hit != hoveredAccessibilityId) {
                    if (hoveredAccessibilityId != View.NO_ID) sendVirtualAccessibilityEvent(hoveredAccessibilityId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hoveredAccessibilityId = hit
                    if (hit != View.NO_ID) sendVirtualAccessibilityEvent(hit, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredAccessibilityId != View.NO_ID) sendVirtualAccessibilityEvent(hoveredAccessibilityId, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                hoveredAccessibilityId = View.NO_ID
                return true
            }
        }
        return super.dispatchHoverEvent(event)
    }

    private fun sendVirtualAccessibilityEvent(id: Int, eventType: Int) {
        if (id == View.NO_ID) return
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = context.packageName
        event.className = Button::class.java.name
        event.contentDescription = virtualNodes().firstOrNull { it.id == id }?.label
        event.setSource(this, id)
        parent?.requestSendAccessibilityEvent(this, event) ?: sendAccessibilityEvent(eventType)
    }

    private fun virtualNodes(): List<VirtualNode> {
        val nodes = mutableListOf<VirtualNode>()
        fun add(id: Int, label: String, left: Float, top: Float, right: Float, bottom: Float, clickable: Boolean = true, selected: Boolean = false) {
            nodes += VirtualNode(id, label, RectF(left, top, right, bottom), clickable, selected)
        }
        when (screen) {
            "home" -> {
                add(1, "Start a new Classic game", 294f,48f,376f,84f)
                add(2, if (hasSavedGame) "Continue saved ${engine.mode.lowercase().replace('_',' ')} game" else "Play Classic game", 220f,352f,345f,394f)
                add(3, "Start Zen mode", 24f,435f,192f,503f)
                add(4, "Start 90 second Time Attack", 208f,435f,376f,503f)
                add(5, "Start Journey mode", 24f,520f,140f,596f)
                add(6, "Start today's Daily puzzle", 142f,520f,258f,596f)
                add(7, "Start Challenge mode", 260f,520f,376f,596f)
                add(8, "Open statistics", 24f,615f,192f,693f)
                add(9, "Open settings", 208f,615f,376f,693f)
            }
            "game" -> {
                add(10, "Return to home", 0f,0f,90f,90f)
                add(11, "${engine.mode.lowercase().replace('_',' ')}. Score ${engine.score}. Best ${engine.best}. ${engine.linesCleared} lines cleared", 24f,90f,376f,210f, false)
                for (row in 0 until GameEngine.SIZE) for (col in 0 until GameEngine.SIZE) {
                    val value = engine.board[row][col]
                    add(100 + row * GameEngine.SIZE + col,
                        "Row ${row + 1}, column ${col + 1}, ${if (value == 0) "empty" else "occupied"}",
                        24f + col * 43f,239f + row * 43f,64f + col * 43f,279f + row * 43f,
                        clickable = !engine.gameOver && !engine.challengeComplete && !timeAttackFinished && (selected >= 0 || pendingBlast.isNotEmpty()))
                }
                if (!engine.gameOver && !engine.challengeComplete && !timeAttackFinished) {
                    val trayX = listOf(54f,164f,274f)
                    for (slot in 0..2) engine.tray[slot]?.let { piece ->
                        add(200 + slot, "Piece ${slot + 1}, ${piece.cells.size} blocks${if (piece.special) ", prism power" else ""}", trayX[slot],626f,trayX[slot]+72f,702f,true,selected == slot)
                    }
                    add(210,"Undo last move",13f,704f,101f,770f)
                    add(211,"Show a legal move hint",108f,704f,196f,770f)
                    add(212,if (engine.energy >= GameEngine.MAX_ENERGY) "Select row blast" else "Row blast unavailable; charge the energy meter",203f,704f,291f,770f,engine.energy >= GameEngine.MAX_ENERGY)
                    add(213,if (engine.energy >= GameEngine.MAX_ENERGY) "Select column blast" else "Column blast unavailable; charge the energy meter",298f,704f,387f,770f,engine.energy >= GameEngine.MAX_ENERGY)
                } else {
                    if (!timeAttackFinished && !gameRecorded) add(210,"Undo the move that ended the game",13f,704f,101f,770f)
                    add(214,"Play this mode again",108f,430f,292f,478f)
                }
            }
            "stats" -> {
                add(300,"Return to home",0f,0f,90f,90f)
                val values = store.stats()
                add(310,"Best score ${values.first}",24f,145f,376f,233f,false)
                add(311,"Games played ${values.second}",24f,257f,376f,345f,false)
                add(312,"Total score ${values.third}",24f,369f,376f,457f,false)
                add(313,if (store.achievements().isEmpty()) "No achievements unlocked yet" else "Achievements: ${store.achievements().joinToString(", ")}",24f,490f,376f,622f,false)
            }
            "settings" -> {
                add(300,"Return to home",0f,0f,90f,90f)
                add(301,if(dark) "Switch to light theme" else "Switch to dark theme",24f,125f,376f,215f)
                add(302,if(haptics) "Turn haptic feedback off" else "Turn haptic feedback on",24f,234f,376f,324f)
                add(303,if(soundEnabled) "Turn sound effects off" else "Turn sound effects on",24f,343f,376f,433f)
            }
        }
        return nodes
    }

    private fun activateVirtualNode(id: Int): Boolean {
        when (id) {
            1 -> startGame("CLASSIC")
            2 -> if (hasSavedGame) resumeGame() else startGame("CLASSIC")
            3 -> startGame("ZEN")
            4 -> startGame("TIME_ATTACK")
            5 -> startGame("JOURNEY")
            6 -> startDaily()
            7 -> startGame("CHALLENGE")
            8 -> screen = "stats"
            9 -> screen = "settings"
            10, 300 -> { if (screen == "game") goHome() else screen = "home" }
            in 100..163 -> {
                val index = id - 100
                handleGameTap(24f + (index % GameEngine.SIZE) * 43f + 20f, 239f + (index / GameEngine.SIZE) * 43f + 20f)
            }
            in 200..202 -> {
                val x = listOf(90f,200f,310f)[id - 200]
                handleGameTap(x,664f)
            }
            210 -> handleGameTap(57f,735f)
            211 -> handleGameTap(152f,735f)
            212 -> handleGameTap(247f,735f)
            213 -> handleGameTap(342f,735f)
            214 -> handleGameTap(200f,454f)
            301 -> { dark = !dark; store.setSetting("theme", if (dark) "dark" else "light"); applySystemBarAppearance() }
            302 -> { haptics = !haptics; store.setSetting("haptics", if (haptics) "on" else "off") }
            303 -> { soundEnabled = !soundEnabled; store.setSetting("sound", if (soundEnabled) "on" else "off") }
            else -> return false
        }
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = (width - insetLeft - insetRight).coerceAtLeast(1)
        val availableHeight = (height - insetTop - insetBottom).coerceAtLeast(1)
        scale = min(availableWidth / 400f, availableHeight / 800f).coerceAtLeast(0.01f)
        offsetX = insetLeft + (availableWidth - 400f * scale) / 2f
        offsetY = insetTop + (availableHeight - 800f * scale) / 2f
        canvas.save(); canvas.translate(offsetX, offsetY); canvas.scale(scale, scale)
        contentDescription = when (screen) {
            "game" -> "BlockVerse ${engine.mode.lowercase().replace('_', ' ')}. Score ${engine.score}. ${engine.linesCleared} lines cleared. ${if (engine.gameOver) "No more moves." else if (engine.challengeComplete || timeAttackFinished) "Game complete." else "Game in progress."}"
            "stats" -> "BlockVerse statistics"
            "settings" -> "BlockVerse settings"
            else -> "BlockVerse home. Offline block puzzle game."
        }
        canvas.drawColor(if (dark) bg else Color.rgb(242,245,252))
        when (screen) {
            "home" -> drawHome(canvas)
            "game" -> drawGame(canvas)
            "stats" -> drawStats(canvas)
            "settings" -> drawSettings(canvas)
        }
        canvas.restore()
    }

    private fun color(c: Int) { paint.color = c; paint.style = Paint.Style.FILL }
    private fun rounded(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int) {
        color(fill); c.drawRoundRect(x,y,x+w,y+h,r,r,paint)
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, col: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        color(col); paint.textSize=size; paint.typeface=if(bold) boldTypeface else regularTypeface; paint.textAlign=align
        c.drawText(value,x,y,paint)
    }
    private fun titleColor() = if (dark) white else Color.rgb(25,32,55)
    private fun drawHome(c: Canvas) {
        text(c,"BLOCKVERSE",28f,74f,17f,0xFF9D91FF.toInt(),true)
        rounded(c,294f,48f,82f,36f,12f,panelColor())
        text(c,"NEW GAME",335f,71f,10f,titleColor(),true,Paint.Align.CENTER)
        text(c,"Think in blocks.",28f,150f,34f,titleColor(),true)
        text(c,"Clear lines. Build your best.",28f,181f,16f,muted)
        rounded(c,24f,207f,352f,210f,26f,0xFF252D50.toInt())
        text(c,"YOUR NEXT MOVE",46f,246f,12f,0xFFB7B0FF.toInt(),true)
        drawMiniBoard(c,47f,266f)
        text(c,if(hasSavedGame) engine.mode.replace('_',' ') else "CLASSIC",220f,306f,22f,white,true)
        text(c,if(hasSavedGame) "Pick up where you left off" else "Endless combo puzzle",220f,332f,12f,0xFFB5BEDA.toInt())
        rounded(c,220f,352f,125f,42f,14f,0xFF796BFF.toInt())
        text(c,if(hasSavedGame) "CONTINUE" else "PLAY NOW",282f,379f,12f,white,true,Paint.Align.CENTER)
        rounded(c,24f,435f,168f,68f,18f,panelColor()); rounded(c,208f,435f,168f,68f,18f,panelColor())
        text(c,"ZEN MODE",42f,465f,15f,titleColor(),true)
        text(c,"Relax and play",42f,487f,11f,muted)
        text(c,"TIME ATTACK",226f,465f,14f,titleColor(),true)
        text(c,"90-second sprint",226f,487f,11f,muted)
        val modes = listOf("JOURNEY", "DAILY", "CHALLENGE")
        val xs = listOf(24f,142f,260f)
        modes.forEachIndexed { i, label ->
            rounded(c,xs[i],520f,116f,76f,16f,panelColor())
            text(c,label,xs[i]+58f,551f,12f,titleColor(),true,Paint.Align.CENTER)
            text(c,when(i){0->"LEVELS";1->"TODAY";else->"8 LINES"},xs[i]+58f,573f,10f,muted,false,Paint.Align.CENTER)
        }
        rounded(c,24f,615f,168f,78f,18f,panelColor()); rounded(c,208f,615f,168f,78f,18f,panelColor())
        text(c,"STATISTICS",43f,647f,14f,titleColor(),true); text(c,"Your progress",43f,671f,11f,muted)
        text(c,"SETTINGS",227f,647f,14f,titleColor(),true); text(c,"Appearance",227f,671f,11f,muted)
        text(c,"100% OFFLINE  •  FREE FOREVER",200f,748f,11f,muted,true,Paint.Align.CENTER)
    }
    private fun panelColor() = if (dark) panel else Color.WHITE
    private fun drawMiniBoard(c: Canvas, originX: Float, originY: Float) {
        val colors = intArrayOf(0xFF6C63FF.toInt(),0xFFFF6B8A.toInt(),0xFF36C9A5.toInt(),0xFFFFB84D.toInt())
        for (r in 0..3) for (col in 0..3) {
            val filled = (r==0 && col<3)||(r==1 && col==0)||(r==2 && col==0)||(r==2 && col==1)||(r==3 && col<3)
            rounded(c,originX+col*29f,originY+r*29f,24f,24f,6f,if(filled) colors[(r+col)%4] else 0xFF343D61.toInt())
        }
    }
    private fun drawGame(c: Canvas) {
        text(c,"‹",25f,66f,36f,titleColor(),false)
        text(c,engine.mode.replace('_',' '),200f,58f,15f,titleColor(),true,Paint.Align.CENTER)
        text(c,"SCORE",200f,105f,11f,muted,true,Paint.Align.CENTER)
        text(c,engine.score.toString(),200f,145f,35f,titleColor(),true,Paint.Align.CENTER)
        rounded(c,24f,161f,352f,48f,15f,panelColor())
        text(c,"BEST  ${engine.best}",43f,191f,12f,muted,true)
        val secondsLeft = (timeRemainingMs / 1000L).coerceAtLeast(0L)
        val timerLabel = "%02d:%02d".format(secondsLeft / 60L, secondsLeft % 60L)
        val status = when(engine.mode) { "JOURNEY" -> "LEVEL ${engine.level}"; "TIME_ATTACK" -> timerLabel; "CHALLENGE" -> "${engine.linesCleared}/8 LINES"; "DAILY" -> "${engine.linesCleared}/5 LINES"; else -> if(engine.combo>1) "${engine.combo}× COMBO" else "${engine.moves} MOVES" }
        text(c,status,357f,191f,12f,0xFF9D91FF.toInt(),true,Paint.Align.RIGHT)
        // Energy meter charges from line clears and powers the row/column blasts.
        rounded(c,24f,218f,352f,8f,4f,if(dark) 0xFF303A5B.toInt() else 0xFFDDE3F0.toInt())
        rounded(c,24f,218f,352f*(engine.energy/100f),8f,4f,0xFF36C9A5.toInt())
        val boardX=24f; val boardY=239f; val cell=40f; val gap=3f
        rounded(c,boardX-5f,boardY-5f,352f,352f,18f,if(dark) 0xFF222B49.toInt() else 0xFFDDE3F0.toInt())
        for (r in 0 until 8) for (col in 0 until 8) {
            val x=boardX+col*(cell+gap); val y=boardY+r*(cell+gap)
            val v=engine.board[r][col]
            rounded(c,x,y,cell,cell,7f,if(v==0) (if(dark) 0xFF303A5B.toInt() else 0xFFE9EDF5.toInt()) else v)
            if (v!=0) { color(0x44FFFFFF.toInt()); c.drawRoundRect(x+3,y+3,x+cell-3,y+10,5f,5f,paint) }
            if (hintCell!=null && hintSlot>=0 && engine.tray[hintSlot]!=null) {
                val p=engine.tray[hintSlot]!!
                if (p.cells.any { it.x+hintCell!!.x==col && it.y+hintCell!!.y==r }) { color(0x88FFFFFF.toInt()); c.drawRoundRect(x+2,y+2,x+cell-2,y+cell-2,6f,6f,paint) }
            }
        }
        text(c,if(engine.energy>=100) "CHOOSE ROW/COLUMN BLAST, THEN TAP THE BOARD" else "CHOOSE A BLOCK",200f,613f,10f,muted,true,Paint.Align.CENTER)
        val slotX=listOf(54f,164f,274f)
        for (i in 0..2) {
            val sx=slotX[i]
            rounded(c,sx,626f,72f,76f,15f,if(selected==i) 0xFF48416F.toInt() else panelColor())
            val p=engine.tray[i]
            if (p!=null) {
                drawPiece(c,p,sx+10f,635f,13f)
                if(p.special) text(c,"✦",sx+62f,640f,15f,0xFFFFD166.toInt(),true,Paint.Align.CENTER)
            }
        }
        val ready=engine.energy>=100
        rounded(c,13f,714f,88f,43f,12f,panelColor()); rounded(c,108f,714f,88f,43f,12f,panelColor())
        rounded(c,203f,714f,88f,43f,12f,if(ready) 0xFF167F69.toInt() else panelColor()); rounded(c,298f,714f,89f,43f,12f,if(ready) 0xFF167F69.toInt() else panelColor())
        text(c,"UNDO",57f,741f,11f,if ((engine.gameOver || engine.challengeComplete || timeAttackFinished) && gameRecorded) muted else titleColor(),true,Paint.Align.CENTER)
        text(c,"HINT",152f,741f,11f,titleColor(),true,Paint.Align.CENTER)
        text(c,"ROW BLAST",247f,741f,10f,if(ready) white else muted,true,Paint.Align.CENTER)
        text(c,"COL BLAST",342f,741f,10f,if(ready) white else muted,true,Paint.Align.CENTER)
        text(c,"ENERGY ${engine.energy}%",200f,780f,10f,muted,true,Paint.Align.CENTER)
        if (dragging && selected in 0..2) drawDragPreview(c)
        if (engine.gameOver || engine.challengeComplete || timeAttackFinished) {
            rounded(c,35f,305f,330f,205f,24f,0xF0161C35.toInt())
            text(c,when { timeAttackFinished -> "TIME UP"; engine.challengeComplete -> "CHALLENGE COMPLETE"; else -> "NO MORE MOVES" },200f,357f,20f,white,true,Paint.Align.CENTER)
            text(c,"Final score  ${engine.score}",200f,395f,15f,0xFFD4D9EA.toInt(),false,Paint.Align.CENTER)
            rounded(c,108f,430f,184f,48f,15f,0xFF796BFF.toInt())
            text(c,"PLAY AGAIN",200f,460f,14f,white,true,Paint.Align.CENTER)
        }
    }
    private fun drawPiece(c: Canvas, p: Piece, x: Float, y: Float, size: Float) {
        val maxX=(p.cells.maxOfOrNull { it.x } ?: 0)+1; val maxY=(p.cells.maxOfOrNull { it.y } ?: 0)+1
        val unit=min(size, min(48f/maxX,48f/maxY))
        p.cells.forEach { rounded(c,x+it.x*unit,y+it.y*unit,unit-2,unit-2,3f,p.color) }
    }

    /** Shows the actual placement footprint under the finger while dragging over the board. */
    private fun drawDragPreview(c: Canvas) {
        val piece = engine.tray.getOrNull(selected) ?: return
        val boardX = 24f
        val boardY = 239f
        val cell = 40f
        val stride = 43f
        if (dragX in boardX..(boardX + 7f * stride + cell) && dragY in boardY..(boardY + 7f * stride + cell)) {
            val col = ((dragX - boardX) / stride).toInt().coerceIn(0, 7)
            val row = ((dragY - boardY) / stride).toInt().coerceIn(0, 7)
            val valid = engine.canPlace(piece, col, row)
            previewPaint.color = if (valid) 0xFF36C9A5.toInt() else 0xFFFF6B8A.toInt()
            previewPaint.alpha = 150
            piece.cells.forEach { offset ->
                val x = col + offset.x
                val y = row + offset.y
                if (x in 0..7 && y in 0..7) {
                    val left = boardX + x * stride
                    val top = boardY + y * stride
                    c.drawRoundRect(left + 2f, top + 2f, left + cell - 2f, top + cell - 2f, 7f, 7f, previewPaint)
                }
            }
        } else {
            drawPiece(c, piece, dragX - 28f, dragY - 28f, 16f)
        }
    }
    private fun drawStats(c: Canvas) {
        text(c,"‹",25f,66f,36f,titleColor()); text(c,"STATISTICS",200f,62f,17f,titleColor(),true,Paint.Align.CENTER)
        val s=store.stats()
        val items=listOf("BEST SCORE" to s.first.toString(),"GAMES PLAYED" to s.second.toString(),"TOTAL SCORE" to s.third.toString())
        items.forEachIndexed { i, item -> val y=145f+i*112f; rounded(c,24f,y,352f,88f,18f,panelColor()); text(c,item.first,48f,y+34f,12f,muted,true); text(c,item.second,48f,y+68f,25f,titleColor(),true) }
        rounded(c,24f,490f,352f,132f,18f,panelColor())
        text(c,"ACHIEVEMENTS",48f,523f,13f,titleColor(),true)
        val earned = store.achievements()
        text(c,if(earned.isEmpty()) "Play a game to unlock your first badge." else earned.take(3).joinToString("  •  "),48f,553f,11f,muted)
        text(c,"${earned.size} badges unlocked",48f,588f,12f,0xFF36C9A5.toInt(),true)
        text(c,"Stats and achievements stay on this device.",200f,670f,11f,muted,false,Paint.Align.CENTER)
    }
    private fun drawSettings(c: Canvas) {
        text(c,"‹",25f,66f,36f,titleColor()); text(c,"SETTINGS",200f,62f,17f,titleColor(),true,Paint.Align.CENTER)
        rounded(c,24f,125f,352f,90f,18f,panelColor()); text(c,"APPEARANCE",46f,161f,14f,titleColor(),true); text(c,if(dark) "Dark theme  •  tap to switch" else "Light theme  •  tap to switch",46f,188f,12f,muted)
        rounded(c,24f,234f,352f,90f,18f,panelColor()); text(c,"HAPTIC FEEDBACK",46f,270f,14f,titleColor(),true); text(c,if(haptics) "On  •  tap to switch off" else "Off  •  tap to switch on",46f,297f,12f,muted)
        rounded(c,24f,343f,352f,90f,18f,panelColor()); text(c,"SOUND EFFECTS",46f,379f,14f,titleColor(),true); text(c,if(soundEnabled) "On  •  tap to switch off" else "Off  •  tap to switch on",46f,406f,12f,muted)
        text(c,"BlockVerse 1.4.0",200f,700f,12f,muted,false,Paint.Align.CENTER)
        text(c,"Offline by design. No tracking or ads.",200f,724f,11f,muted,false,Paint.Align.CENTER)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x=(event.x-offsetX)/scale; val y=(event.y-offsetY)/scale
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=x; downY=y; dragX=x; dragY=y; dragging=false; gestureCancelled=false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (gestureCancelled) return true
                if (screen=="game" && downY in 626f..705f && (kotlin.math.abs(x-downX)+kotlin.math.abs(y-downY)>10f)) {
                    val slot=when(downX) { in 35f..130f -> 0; in 145f..240f -> 1; in 255f..350f -> 2; else -> -1 }
                    if(slot>=0 && engine.tray[slot]!=null) { selected=slot; dragging=true; dragX=x; dragY=y; invalidate() }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Cancel the whole multi-touch gesture; the final ACTION_UP must not place a piece.
                if (dragging) selected = -1
                dragging = false
                gestureCancelled = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) selected = -1; dragging=false; gestureCancelled=false; invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                if (gestureCancelled) {
                    gestureCancelled = false
                    dragging = false
                    invalidate()
                    return true
                }
                if (screen=="home") {
                    when {
                        y in 48f..84f && x>290f -> startGame("CLASSIC")
                        y in 345f..420f && x>200f -> if(hasSavedGame) resumeGame() else startGame("CLASSIC")
                        y in 435f..510f && x<200f -> startGame("ZEN")
                        y in 435f..510f && x>=200f -> startGame("TIME_ATTACK")
                        y in 520f..600f && x<140f -> startGame("JOURNEY")
                        y in 520f..600f && x in 142f..258f -> startDaily()
                        y in 520f..600f && x>258f -> startGame("CHALLENGE")
                        y in 615f..700f && x<200f -> screen="stats"
                        y in 615f..700f && x>=200f -> screen="settings"
                    }
                } else if (screen=="stats" || screen=="settings") {
                    if (y<95f) screen="home" else if (screen=="settings" && y in 125f..215f) { dark=!dark; store.setSetting("theme",if(dark) "dark" else "light"); applySystemBarAppearance() } else if (screen=="settings" && y in 234f..324f) { haptics=!haptics; store.setSetting("haptics",if(haptics) "on" else "off") } else if (screen=="settings" && y in 343f..433f) { soundEnabled=!soundEnabled; store.setSetting("sound",if(soundEnabled) "on" else "off") }
                } else if (screen=="game") {
                    if(dragging) {
                        val slot=when(downX) { in 35f..130f -> 0; in 145f..240f -> 1; in 255f..350f -> 2; else -> -1 }
                        selected=slot
                        if(x in 19f..376f && y in 234f..590f) handleGameTap(x,y)
                        else if(slot>=0 && engine.tray[slot]!=null) handleGameTap(downX,downY)
                    } else handleGameTap(x,y)
                    dragging=false
                }
                performClick(); invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); return true
            }
        }
        return true
    }
    private fun startGame(mode: String) {
        pauseTimer()
        engine.start(mode); selected=-1; pendingBlast=""; hintCell=null; hintSlot=-1; gameRecorded=false; hasActiveGame=true; sessionId=UUID.randomUUID().toString(); screen="game"
        timeRemainingMs = if(mode=="TIME_ATTACK") 90_000L else 0L; timeAttackFinished=false
        if(mode=="TIME_ATTACK") startTimer()
        persist()
    }
    private fun resumeGame() {
        selected=-1; pendingBlast=""; dragging=false; hintCell=null; hintSlot=-1; gameRecorded=store.isSessionFinished(sessionId); screen="game"; resumeTimer(); invalidate()
    }
    private fun startDaily() {
        pauseTimer()
        val calendar = Calendar.getInstance()
        val seed = calendar.get(Calendar.YEAR).toLong() * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
        engine.start("DAILY", seed)
        hasActiveGame=true; timeAttackFinished=false; timeRemainingMs=0L
        selected=-1; pendingBlast=""; hintCell=null; hintSlot=-1; gameRecorded=false; sessionId=UUID.randomUUID().toString(); screen="game"; persist()
    }
    private fun handleGameTap(x: Float,y: Float) {
        if (y<90f && x<80f) {
            pauseTimer()
            if (engine.gameOver || engine.challengeComplete || timeAttackFinished) recordCurrentGame()
            screen="home"; persist(); return
        }
        if (engine.gameOver || engine.challengeComplete || timeAttackFinished) {
            // A normal board/challenge end remains undoable until the player leaves or restarts.
            if (!timeAttackFinished && !gameRecorded && y in 704f..770f && x in 13f..101f) {
                if (engine.undo()) { selected=-1; hintCell=null; hintSlot=-1; hasSavedGame=true; persist(); resumeTimer() }
                return
            }
            if (y in 305f..510f) {
                if (!recordCurrentGame()) return
                if(engine.mode=="DAILY") startDaily() else startGame(engine.mode)
            }
            return
        }
        if (y in 626f..705f) {
            val slot=when(x) { in 35f..130f -> 0; in 145f..240f -> 1; in 255f..350f -> 2; else -> -1 }
            if(slot>=0 && engine.tray[slot]!=null) { selected=slot; hintCell=null }
            return
        }
        if (y in 704f..770f) {
            when(x) {
                in 13f..101f -> { if(engine.undo()) { selected=-1; hintCell=null; persist() } }
                in 108f..196f -> { val h=engine.hint(); if(h!=null) { hintSlot=h.first; hintCell=h.second; selected=h.first } }
                in 203f..291f -> { if(engine.energy>=100) { pendingBlast="ROW"; selected=-1 } }
                in 298f..387f -> { if(engine.energy>=100) { pendingBlast="COLUMN"; selected=-1 } }
            }
            return
        }
        if (x in 19f..376f && y in 234f..590f && pendingBlast.isNotEmpty()) {
            val col=((x-24f)/43f).toInt().coerceIn(0,7); val row=((y-239f)/43f).toInt().coerceIn(0,7)
            val used = if(pendingBlast=="ROW") engine.useRowBlast(row) else engine.useColumnBlast(col)
            pendingBlast=""
            if(used) {
                persist(); feedback(); playSound(clearSound)
                if(engine.gameOver || engine.challengeComplete) { pauseTimer() }
            }
            return
        }
        if (selected>=0 && x in 19f..376f && y in 234f..590f) {
            val col=((x-24f)/43f).toInt(); val row=((y-239f)/43f).toInt()
            val previousLines = engine.linesCleared
            if (engine.place(selected,col,row)) {
                selected=-1; hintCell=null; hintSlot=-1; persist(); feedback()
                if(engine.linesCleared>previousLines) playSound(if(engine.combo>1) comboSound else clearSound) else playSound(placeSound)
                if(engine.gameOver || engine.challengeComplete) { pauseTimer() }
            }
        }
    }

}
