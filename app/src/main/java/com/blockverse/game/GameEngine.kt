package com.blockverse.game

import kotlin.math.max

private const val BLOCKVERSE_DEFAULT_RANDOM_STATE: Long = 0x4D595DF4D0F33173L

data class Cell(val x: Int, val y: Int)
data class Piece(val cells: List<Cell>, val color: Int, val id: Int, val special: Boolean = false)
data class GameSnapshot(
    val board: Array<IntArray>, val tray: List<Piece?>, val score: Int,
    val best: Int, val combo: Int, val mode: String, val gameOver: Boolean,
    val level: Int = 1, val linesCleared: Int = 0, val moves: Int = 0,
    val energy: Int = 0, val nextId: Int = 1, val lastCleared: Int = 0,
    val powerupsUsed: Int = 0, val randomState: Long = BLOCKVERSE_DEFAULT_RANDOM_STATE
)

/** Deterministic, platform-independent puzzle rules. All persisted input is validated before commit. */
class GameEngine(seed: Long = (System.nanoTime() xor System.currentTimeMillis())) {
    companion object {
        const val SIZE = 8
        const val MAX_ENERGY = 100
        const val STATE_VERSION = 3
        const val MAX_SCORE = Int.MAX_VALUE
        const val MAX_LEVEL = 1_000_000
        const val MAX_COUNTER = 10_000_000
        const val DEFAULT_RANDOM_STATE: Long = BLOCKVERSE_DEFAULT_RANDOM_STATE
        val MODES = setOf("CLASSIC", "ZEN", "JOURNEY", "TIME_ATTACK", "CHALLENGE", "DAILY")
        val PALETTE = intArrayOf(
            0xFF6C63FF.toInt(), 0xFFFF6B8A.toInt(), 0xFF36C9A5.toInt(),
            0xFFFFB84D.toInt(), 0xFF43A5FF.toInt(), 0xFFB47CFF.toInt(), 0xFFFF8266.toInt()
        )
        val SHAPES: List<List<Cell>> = listOf(
            listOf(Cell(0,0)), listOf(Cell(0,0),Cell(1,0)), listOf(Cell(0,0),Cell(0,1)),
            listOf(Cell(0,0),Cell(1,0),Cell(2,0)), listOf(Cell(0,0),Cell(0,1),Cell(0,2)),
            listOf(Cell(0,0),Cell(1,0),Cell(0,1),Cell(1,1)),
            listOf(Cell(0,0),Cell(1,0),Cell(2,0),Cell(1,1)),
            listOf(Cell(1,0),Cell(0,1),Cell(1,1),Cell(2,1)),
            listOf(Cell(0,0),Cell(1,0),Cell(1,1),Cell(2,1)),
            listOf(Cell(0,0),Cell(0,1),Cell(1,1),Cell(1,2)),
            listOf(Cell(0,0),Cell(1,0),Cell(2,0),Cell(0,1),Cell(1,1),Cell(2,1)),
            listOf(Cell(0,0),Cell(0,1),Cell(0,2),Cell(1,2),Cell(2,2)),
            listOf(Cell(0,0),Cell(1,0),Cell(2,0),Cell(3,0)),
            listOf(Cell(0,0),Cell(0,1),Cell(0,2),Cell(0,3)),
            listOf(Cell(0,0),Cell(1,0),Cell(2,0),Cell(1,1),Cell(1,2))
        )
        private val PALETTE_SET = PALETTE.toSet()
        private val SHAPE_KEYS = SHAPES.map(::shapeKey).toSet()
        private fun shapeKey(cells: List<Cell>) = cells.joinToString(";") { "${it.x},${it.y}" }
    }

    private var randomState: Long = normalizeSeed(seed)
    var board: Array<IntArray> = Array(SIZE) { IntArray(SIZE) }; private set
    var tray: MutableList<Piece?> = MutableList(3) { null }; private set
    var score = 0; private set
    var best = 0; set(value) { field = max(field, value.coerceAtLeast(0)) }
    var combo = 0; private set
    var mode = "CLASSIC"; private set
    var gameOver = false; private set
    var level = 1; private set
    var linesCleared = 0; private set
    var moves = 0; private set
    var energy = 0; private set
    var powerupsUsed = 0; private set
    private var nextId = 1
    private var undoSnapshot: GameSnapshot? = null
    private var lastCleared = 0

    private fun normalizeSeed(value: Long): Long = if (value == 0L) DEFAULT_RANDOM_STATE else value

    /** xorshift64*: explicit state makes a saved game reproduce the exact future tray. */
    private fun nextRandomLong(): Long {
        var x = randomState
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        randomState = normalizeSeed(x)
        return x * 0x2545F4914F6CDD1DL
    }

    private fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextRandomLong() ushr 1) % bound.toLong()).toInt()
    }

    fun start(newMode: String = "CLASSIC", seed: Long? = null) {
        if (seed != null) randomState = normalizeSeed(seed)
        mode = newMode.takeIf { it in MODES } ?: "CLASSIC"
        board = Array(SIZE) { IntArray(SIZE) }
        score = 0; combo = 0; gameOver = false; undoSnapshot = null; lastCleared = 0
        level = 1; linesCleared = 0; moves = 0; energy = 0; powerupsUsed = 0; nextId = 1
        refillTray(); updateGameOver()
    }

    private fun makePiece(): Piece = Piece(SHAPES[nextInt(SHAPES.size)], PALETTE[nextInt(PALETTE.size)], nextId++, nextInt(100) < 7)
    private fun refillTray() {
        // Piece IDs only identify the three currently offered tray pieces; recycling per tray
        // prevents integer wrap in extremely long offline sessions.
        nextId = 1
        tray = MutableList(3) { makePiece() }
    }
    private fun addScore(amount: Long) { score = (score.toLong() + amount.coerceAtLeast(0L)).coerceAtMost(MAX_SCORE.toLong()).toInt() }

    private fun maximumPlausibleScore(moveCount: Int): Long {
        val n = moveCount.coerceIn(0, MAX_COUNTER).toLong()
        // Upper bound: 6 placed cells + 9 prism cells + 16 cleared lines, plus a
        // worst-case increasing combo bonus on every move. Saturate at Int score limit.
        val base = n * 460L
        val comboBonus = 15L * n * (n - 1L) / 2L
        return (base + comboBonus).coerceAtMost(MAX_SCORE.toLong())
    }

    fun canPlace(piece: Piece, ox: Int, oy: Int): Boolean = piece.cells.isNotEmpty() && piece.cells.all { c ->
        val x = ox + c.x; val y = oy + c.y
        x in 0 until SIZE && y in 0 until SIZE && board[y][x] == 0
    }

    fun place(slot: Int, x: Int, y: Int): Boolean {
        if (gameOver || challengeComplete || slot !in tray.indices) return false
        val piece = tray[slot] ?: return false
        if (!canPlace(piece, x, y)) return false
        undoSnapshot = snapshot()
        piece.cells.forEach { c -> board[y + c.y][x + c.x] = piece.color }
        addScore(piece.cells.size.toLong())
        moves = (moves + 1).coerceAtMost(MAX_COUNTER)
        tray[slot] = null

        if (piece.special) {
            for (r in max(0, y - 1)..minOf(SIZE - 1, y + 1))
                for (c in max(0, x - 1)..minOf(SIZE - 1, x + 1)) if (board[r][c] != 0) {
                    board[r][c] = 0
                    addScore(2)
                }
        }

        val rows = (0 until SIZE).filter { r -> (0 until SIZE).all { board[r][it] != 0 } }
        val cols = (0 until SIZE).filter { c -> (0 until SIZE).all { board[it][c] != 0 } }
        val cleared = rows.size + cols.size
        if (cleared > 0) {
            rows.forEach { r -> board[r].fill(0) }
            cols.forEach { c -> for (r in 0 until SIZE) board[r][c] = 0 }
            combo = if (lastCleared > 0) (combo + 1).coerceAtMost(MAX_COUNTER) else 1
            linesCleared = (linesCleared + cleared).coerceAtMost(MAX_COUNTER)
            val points = cleared.toLong() * SIZE * 2L + max(0, cleared - 1).toLong() * 12L + (combo - 1).toLong() * 15L
            addScore(points)
            energy = minOf(MAX_ENERGY, energy + cleared * 20)
            updateJourneyLevel()
        } else combo = 0
        lastCleared = cleared
        best = max(best, score)
        if (tray.all { it == null }) refillTray()
        updateGameOver()
        return true
    }

    private fun expectedJourneyLevel(value: Int): Int {
        if (value < 120) return 1
        return (2L + (value.toLong() - 120L) / 80L).coerceIn(1L, MAX_LEVEL.toLong()).toInt()
    }

    private fun updateJourneyLevel() {
        if (mode == "JOURNEY") level = expectedJourneyLevel(score)
    }

    val challengeComplete: Boolean
        get() = when (mode) {
            "CHALLENGE" -> linesCleared >= 8
            "DAILY" -> linesCleared >= 5
            else -> false
        }

    fun useRowBlast(row: Int): Boolean {
        if (gameOver || challengeComplete || energy < MAX_ENERGY || row !in 0 until SIZE || board[row].all { it == 0 }) return false
        undoSnapshot = snapshot()
        val removed = board[row].count { it != 0 }
        board[row].fill(0); energy = 0; powerupsUsed = (powerupsUsed + 1).coerceAtMost(MAX_COUNTER)
        addScore(removed.toLong() * 2L); moves = (moves + 1).coerceAtMost(MAX_COUNTER)
        combo = if (lastCleared > 0) (combo + 1).coerceAtMost(MAX_COUNTER) else 1
        linesCleared = (linesCleared + 1).coerceAtMost(MAX_COUNTER); lastCleared = 1
        best = max(best, score); updateJourneyLevel(); updateGameOver(); return true
    }

    fun useColumnBlast(column: Int): Boolean {
        if (gameOver || challengeComplete || energy < MAX_ENERGY || column !in 0 until SIZE || (0 until SIZE).all { board[it][column] == 0 }) return false
        undoSnapshot = snapshot()
        var removed = 0
        for (r in 0 until SIZE) if (board[r][column] != 0) { board[r][column] = 0; removed++ }
        energy = 0; powerupsUsed = (powerupsUsed + 1).coerceAtMost(MAX_COUNTER)
        addScore(removed.toLong() * 2L); moves = (moves + 1).coerceAtMost(MAX_COUNTER)
        combo = if (lastCleared > 0) (combo + 1).coerceAtMost(MAX_COUNTER) else 1
        linesCleared = (linesCleared + 1).coerceAtMost(MAX_COUNTER); lastCleared = 1
        best = max(best, score); updateJourneyLevel(); updateGameOver(); return true
    }

    fun undo(): Boolean {
        val old = undoSnapshot ?: return false
        val currentBest = best
        restore(old)
        best = currentBest
        undoSnapshot = null
        return true
    }

    fun hint(): Pair<Int, Cell>? {
        for (slot in tray.indices) {
            val piece = tray[slot] ?: continue
            for (y in 0 until SIZE) for (x in 0 until SIZE) if (canPlace(piece, x, y)) return slot to Cell(x,y)
        }
        return null
    }

    fun hasAnyMove(): Boolean = tray.filterNotNull().any { p ->
        (0 until SIZE).any { y -> (0 until SIZE).any { x -> canPlace(p, x, y) } }
    }

    private fun updateGameOver() { gameOver = !hasAnyMove() }

    fun snapshot() = GameSnapshot(
        Array(SIZE) { board[it].clone() }, tray.toList(), score, best, combo, mode, gameOver,
        level, linesCleared, moves, energy, nextId, lastCleared, powerupsUsed, randomState
    )

    fun restore(s: GameSnapshot) {
        require(isValidSnapshot(s)) { "Invalid game snapshot" }
        board = Array(SIZE) { s.board[it].clone() }; tray = s.tray.toMutableList()
        score = s.score; best = max(best, s.best); combo = s.combo; mode = s.mode
        level = s.level; linesCleared = s.linesCleared; moves = s.moves; energy = s.energy
        nextId = s.nextId; lastCleared = s.lastCleared; powerupsUsed = s.powerupsUsed
        randomState = normalizeSeed(s.randomState)
        undoSnapshot = null
        updateGameOver()
    }

    private fun isValidPiece(piece: Piece): Boolean =
        piece.id > 0 && piece.color in PALETTE_SET && piece.cells.isNotEmpty() &&
            piece.cells.distinct().size == piece.cells.size &&
            piece.cells.all { it.x in 0..3 && it.y in 0..3 } &&
            shapeKey(piece.cells) in SHAPE_KEYS

    private fun isValidSnapshot(s: GameSnapshot): Boolean {
        if (s.board.size != SIZE || s.board.any { it.size != SIZE }) return false
        if (s.board.any { row -> row.any { it != 0 && it !in PALETTE_SET } }) return false
        if (s.tray.size != 3 || s.tray.all { it == null } || s.tray.filterNotNull().any { !isValidPiece(it) }) return false
        val ids = s.tray.filterNotNull().map { it.id }
        if (ids.distinct().size != ids.size || s.nextId !in 1..MAX_COUNTER || s.nextId <= (ids.maxOrNull() ?: 0)) return false
        if (s.mode !in MODES || s.score < 0 || s.best < s.score || s.score.toLong() > maximumPlausibleScore(s.moves) || s.combo !in 0..MAX_COUNTER || s.combo > s.moves) return false
        if (s.level !in 1..MAX_LEVEL || (s.mode == "JOURNEY" && s.level != expectedJourneyLevel(s.score)) || (s.mode != "JOURNEY" && s.level != 1)) return false
        if (s.linesCleared !in 0..MAX_COUNTER || s.moves !in 0..MAX_COUNTER || s.linesCleared.toLong() > s.moves.toLong() * SIZE * 2L) return false
        if ((s.lastCleared == 0 && s.combo != 0) || (s.lastCleared > 0 && s.combo == 0)) return false
        if (s.energy !in 0..MAX_ENERGY || s.lastCleared !in 0..(SIZE * 2) || s.powerupsUsed !in 0..s.moves) return false
        if (s.randomState == 0L) return false
        val calculatedGameOver = s.tray.filterNotNull().none { piece ->
            (0 until SIZE).any { y -> (0 until SIZE).any { x ->
                piece.cells.all { cell -> x + cell.x in 0 until SIZE && y + cell.y in 0 until SIZE && s.board[y + cell.y][x + cell.x] == 0 }
            } }
        }
        return s.gameOver == calculatedGameOver
    }

    fun exportState(): String {
        val cells = board.joinToString(";") { row -> row.joinToString(",") }
        val pieces = tray.joinToString(";") { p -> if (p == null) "-" else "${p.color}:${p.cells.joinToString(".") { "${it.x},${it.y}" }}:${p.id}:${if (p.special) 1 else 0}" }
        return listOf(STATE_VERSION, mode, score, best, combo, if (gameOver) 1 else 0, cells, pieces,
            nextId, lastCleared, level, linesCleared, moves, energy, powerupsUsed, randomState).joinToString("|")
    }

    /** Import is atomic: every field and invariant is checked before live state changes. */
    fun importState(raw: String): Boolean = runCatching {
        require(raw.length <= 16_384)
        val p = raw.split('|')
        require(p.size in 10..16)
        val version = p[0].toInt()
        require(version in 1..STATE_VERSION)
        require(when (version) { 1 -> p.size == 10; 2 -> p.size == 14 || p.size == 15; else -> p.size == 16 })
        require(p[5] == "0" || p[5] == "1")
        val rows = p[6].split(';'); require(rows.size == SIZE)
        val parsedBoard = Array(SIZE) { y ->
            val values = rows[y].split(','); require(values.size == SIZE)
            IntArray(SIZE) { x -> values[x].toInt().also { require(it == 0 || it in PALETTE_SET) } }
        }
        val parsedTray = p[7].split(';').map { value ->
            if (value == "-") null else {
                val parts = value.split(':'); require(parts.size == 3 || parts.size == 4)
                val cells = parts[1].split('.').map { xy ->
                    val pair = xy.split(','); require(pair.size == 2)
                    Cell(pair[0].toInt(), pair[1].toInt())
                }
                val piece = Piece(cells, parts[0].toInt(), parts[2].toInt(), parts.getOrNull(3) == "1")
                require(parts.size == 3 || parts[3] == "0" || parts[3] == "1")
                require(isValidPiece(piece))
                piece
            }
        }
        require(parsedTray.size == 3 && parsedTray.any { it != null })
        val parsedMode = p[1]; require(parsedMode in MODES)
        val parsedScore = p[2].toInt(); val parsedBest = p[3].toInt(); val parsedCombo = p[4].toInt()
        val parsedNextId = p[8].toInt(); val parsedLastCleared = p[9].toInt()
        val storedLevel = if (version >= 2 && p.size >= 14) p[10].toInt() else 1
        val parsedLevel = if (version < 3 && parsedMode == "JOURNEY") expectedJourneyLevel(parsedScore) else storedLevel
        val parsedLines = if (version >= 2 && p.size >= 14) p[11].toInt() else 0
        val parsedMoves = if (version >= 2 && p.size >= 14) p[12].toInt() else maxOf(parsedCombo, ((parsedScore.toLong() + 459L) / 460L).coerceAtMost(MAX_COUNTER.toLong()).toInt())
        val parsedEnergy = if (version >= 2 && p.size >= 14) p[13].toInt() else 0
        val parsedPowerups = if (version >= 2 && p.size >= 15) p[14].toInt() else 0
        val parsedRandom = if (version >= 3 && p.size == 16) p[15].toLong() else normalizeSeed(raw.hashCode().toLong() xor DEFAULT_RANDOM_STATE)
        require(parsedScore >= 0 && parsedBest >= parsedScore && parsedCombo in 0..MAX_COUNTER && parsedNextId in 1..MAX_COUNTER)
        require(parsedLevel in 1..MAX_LEVEL && parsedLines in 0..MAX_COUNTER && parsedMoves in 0..MAX_COUNTER)
        require(parsedCombo <= parsedMoves && parsedLines.toLong() <= parsedMoves.toLong() * SIZE * 2L)
        require(parsedScore.toLong() <= maximumPlausibleScore(parsedMoves))
        require((parsedLastCleared == 0 && parsedCombo == 0) || (parsedLastCleared > 0 && parsedCombo > 0))
        require((parsedMode == "JOURNEY" && parsedLevel == expectedJourneyLevel(parsedScore)) || (parsedMode != "JOURNEY" && parsedLevel == 1))
        require(parsedEnergy in 0..MAX_ENERGY && parsedPowerups in 0..parsedMoves && parsedLastCleared in 0..(SIZE * 2))
        require(parsedRandom != 0L)
        val ids = parsedTray.filterNotNull().map { it.id }
        require(ids.distinct().size == ids.size && parsedNextId > (ids.maxOrNull() ?: 0))
        val computedGameOver = parsedTray.filterNotNull().none { piece ->
            (0 until SIZE).any { y -> (0 until SIZE).any { x ->
                piece.cells.all { cell -> x + cell.x in 0 until SIZE && y + cell.y in 0 until SIZE && parsedBoard[y + cell.y][x + cell.x] == 0 }
            } }
        }
        require((p[5] == "1") == computedGameOver)

        board = parsedBoard; tray = parsedTray.toMutableList(); mode = parsedMode
        score = parsedScore; best = max(best, parsedBest); combo = parsedCombo; gameOver = computedGameOver
        nextId = parsedNextId; lastCleared = parsedLastCleared; level = parsedLevel
        linesCleared = parsedLines; moves = parsedMoves; energy = parsedEnergy; powerupsUsed = parsedPowerups
        randomState = normalizeSeed(parsedRandom)
        undoSnapshot = null
        true
    }.getOrDefault(false)
}
