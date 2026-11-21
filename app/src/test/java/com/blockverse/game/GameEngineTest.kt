package com.blockverse.game

import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {
    private val fill = GameEngine.PALETTE[0]
    private val pieceColor = GameEngine.PALETTE[1]

    @Test fun freshGameHasEmptyBoardAndThreePieces() {
        val game = GameEngine(1L); game.start()
        assertEquals(8, game.board.size)
        assertTrue(game.board.all { row -> row.all { it == 0 } })
        assertEquals(3, game.tray.size)
        assertFalse(game.gameOver)
    }

    @Test fun invalidPlacementDoesNotChangeScoreOrConsumePiece() {
        val game = GameEngine(2L); game.start()
        val piece = game.tray[0]!!
        assertFalse(game.place(0, 8, 8))
        assertEquals(0, game.score)
        assertEquals(piece.id, game.tray[0]!!.id)
    }

    @Test fun undoRestoresPreviousStateIncludingRandomSequence() {
        val game = GameEngine(3L); game.start()
        val before = game.exportState().split('|')
        assertTrue(game.place(0, 0, 0))
        assertTrue(game.undo())
        val after = game.exportState().split('|')
        assertEquals(before[2], after[2])
        assertEquals(before[6], after[6])
        assertEquals(before[7], after[7])
        assertEquals(before[15], after[15])
        assertFalse(game.undo())
    }

    @Test fun savedStateRoundTrips() {
        val first = GameEngine(4L); first.start("ZEN")
        val second = GameEngine(5L)
        assertTrue(second.importState(first.exportState()))
        assertEquals(first.score, second.score)
        assertEquals(first.mode, second.mode)
        assertEquals(first.tray.map { it?.id }, second.tray.map { it?.id })
        assertEquals(first.exportState(), second.exportState())
    }

    @Test fun completingRowAndColumnClearsBoth() {
        val game = GameEngine(6L); game.start()
        for (i in 1 until 8) { game.board[0][i] = fill; game.board[i][0] = fill }
        val original = game.tray[0]!!
        game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, original.id)
        assertTrue(game.place(0,0,0))
        assertTrue((0 until 8).all { game.board[0][it] == 0 })
        assertTrue((0 until 8).all { game.board[it][0] == 0 })
        assertEquals(2, game.linesCleared)
    }

    @Test fun prismPieceClearsItsThreeByThreeArea() {
        val game = GameEngine(7L); game.start()
        for (r in 2..4) for (c in 2..4) if (!(r == 3 && c == 3)) game.board[r][c] = fill
        val original = game.tray[0]!!
        game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, original.id, true)
        assertTrue(game.place(0,3,3))
        assertTrue((2..4).all { r -> (2..4).all { c -> game.board[r][c] == 0 } })
    }

    @Test fun chargedBlastConsumesEnergyAndCanBeUndone() {
        val game = GameEngine(8L); game.start()
        val reusableId = game.tray[0]!!.id
        repeat(5) { row ->
            for (c in 1 until 8) game.board[row][c] = fill
            game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, reusableId)
            assertTrue(game.place(0,0,row))
        }
        assertEquals(GameEngine.MAX_ENERGY, game.energy)
        game.board[7][0] = fill; game.board[7][1] = fill
        assertTrue(game.useRowBlast(7))
        assertEquals(0, game.energy)
        assertTrue((0 until 8).all { game.board[7][it] == 0 })
        assertTrue(game.undo())
        assertEquals(GameEngine.MAX_ENERGY, game.energy)
    }

    @Test fun stateRoundTripPreservesProgressAndSpecialPieces() {
        val first = GameEngine(9L); first.start("JOURNEY")
        val original = first.tray[0]!!
        first.tray[0] = original.copy(special = true)
        first.board[5][5] = fill
        val second = GameEngine(10L)
        assertTrue(second.importState(first.exportState()))
        assertEquals("JOURNEY", second.mode)
        assertEquals(original.id, second.tray[0]!!.id)
        assertTrue(second.tray[0]!!.special)
        assertEquals(first.board[5][5], second.board[5][5])
    }

    @Test fun dailyModeAndRestoredContinuationAreDeterministic() {
        val first = GameEngine(11L); first.start("DAILY", 20261121L)
        val second = GameEngine(99L); second.start("DAILY", 20261121L)
        assertEquals(first.tray.map { it?.cells }, second.tray.map { it?.cells })
        assertEquals(first.tray.map { it?.color }, second.tray.map { it?.color })
        val originals = first.tray.map { it!! }
        first.tray.indices.forEach { slot -> first.tray[slot] = Piece(listOf(Cell(0,0)), originals[slot].color, originals[slot].id) }
        assertTrue(second.importState(first.exportState()))
        assertTrue(first.place(0,0,0))
        assertTrue(second.importState(first.exportState()))
        repeat(2) {
            val h = first.hint()!!; assertTrue(first.place(h.first, h.second.x, h.second.y))
        }
        repeat(2) {
            val h = second.hint()!!; assertTrue(second.place(h.first, h.second.x, h.second.y))
        }
        assertEquals(first.tray.map { it?.cells }, second.tray.map { it?.cells })
        assertEquals(first.tray.map { it?.color }, second.tray.map { it?.color })
        assertEquals(first.exportState(), second.exportState())
    }

    @Test fun malformedSaveDoesNotPartiallyOverwriteGame() {
        val game = GameEngine(12L); game.start()
        val before = game.exportState()
        val malformed = before.split('|').toMutableList().apply { this[2] = "not-a-number" }.joinToString("|")
        assertFalse(game.importState(malformed))
        assertEquals(before, game.exportState())
    }

    @Test fun invalidBoardColorIsRejectedAtomically() {
        val game = GameEngine(13L); game.start()
        game.board[0][0] = 999999
        val payload = game.exportState()
        val target = GameEngine(14L); target.start()
        val before = target.exportState()
        assertFalse(target.importState(payload))
        assertEquals(before, target.exportState())
    }

    @Test fun emptyTraySaveIsRejected() {
        val game = GameEngine(15L); game.start()
        game.tray.indices.forEach { game.tray[it] = null }
        assertFalse(GameEngine(16L).importState(game.exportState()))
    }

    @Test fun gameOverFlagMustMatchActualLegalMoves() {
        val game = GameEngine(17L); game.start()
        val payload = game.exportState().split('|').toMutableList().apply { this[5] = "1" }.joinToString("|")
        assertFalse(GameEngine(18L).importState(payload))
    }

    @Test fun fullBoardWithNoLegalTrayPlacementRestoresAsGameOver() {
        val source = GameEngine(19L); source.start()
        for (r in 0 until GameEngine.SIZE) for (c in 0 until GameEngine.SIZE) source.board[r][c] = fill
        val originals = source.tray.map { it!! }
        val square = listOf(Cell(0,0),Cell(1,0),Cell(0,1),Cell(1,1))
        source.tray[0] = Piece(square, pieceColor, originals[0].id)
        source.tray[1] = Piece(square, pieceColor, originals[1].id)
        source.tray[2] = Piece(square, pieceColor, originals[2].id)
        val terminalPayload = source.exportState().split('|').toMutableList().apply { this[5] = "1" }.joinToString("|")
        val restored = GameEngine(20L)
        assertTrue(restored.importState(terminalPayload))
        assertTrue(restored.gameOver)
        assertFalse(restored.hasAnyMove())
    }

    @Test fun challengeCompletesAtEightClearedLinesAndCanUndo() {
        val game = GameEngine(20L); game.start("CHALLENGE")
        val id = game.tray[0]!!.id
        repeat(8) { row ->
            for (c in 1 until 8) game.board[row][c] = fill
            game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, id)
            assertTrue(game.place(0,0,row))
        }
        assertTrue(game.challengeComplete)
        assertEquals(8, game.linesCleared)
        assertTrue(game.undo())
        assertFalse(game.challengeComplete)
    }

    @Test fun journeyLevelCalculationCannotOverflowOrLoop() {
        val game = GameEngine(21L); game.start("JOURNEY")
        val parts = game.exportState().split('|').toMutableList()
        parts[2] = Int.MAX_VALUE.toString(); parts[3] = Int.MAX_VALUE.toString()
        parts[12] = GameEngine.MAX_COUNTER.toString()
        parts[10] = GameEngine.MAX_LEVEL.toString()
        val highScore = parts.joinToString("|")
        assertTrue(game.importState(highScore))
        assertTrue(game.level <= GameEngine.MAX_LEVEL)
        assertTrue(game.score >= 0)
    }

    @Test fun scoreSaturatesInsteadOfWrappingNegative() {
        val game = GameEngine(22L); game.start("CLASSIC")
        val parts = game.exportState().split('|').toMutableList()
        parts[2] = Int.MAX_VALUE.toString(); parts[3] = Int.MAX_VALUE.toString()
        parts[12] = GameEngine.MAX_COUNTER.toString()
        assertTrue(game.importState(parts.joinToString("|")))
        val h = game.hint()!!
        assertTrue(game.place(h.first, h.second.x, h.second.y))
        assertEquals(Int.MAX_VALUE, game.score)
    }

    @Test fun powerupStartsComboAtOneInsteadOfInventingAChain() {
        val game = GameEngine(23L); game.start()
        val reusableId = game.tray[0]!!.id
        repeat(5) { row ->
            for (c in 1 until 8) game.board[row][c] = fill
            game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, reusableId)
            assertTrue(game.place(0,0,row))
        }
        game.tray[0] = Piece(listOf(Cell(0,0)), pieceColor, reusableId)
        assertTrue(game.place(0,7,7)) // A non-clearing move breaks the combo chain.
        game.board[7][0] = fill
        assertTrue(game.useRowBlast(7))
        assertEquals(1, game.combo)
    }

    @Test fun timeAttackModeIsPersistableOffline() {
        val first = GameEngine(24L); first.start("TIME_ATTACK")
        val second = GameEngine(25L)
        assertTrue(second.importState(first.exportState()))
        assertEquals("TIME_ATTACK", second.mode)
    }
    @Test fun versionTwoSaveMigratesAndKeepsCoreState() {
        val source = GameEngine(26L); source.start("DAILY", 20261121L)
        val legacyV2 = source.exportState().split('|').toMutableList().apply {
            this[0] = "2"
            removeAt(lastIndex) // v2 did not persist the PRNG state.
        }.joinToString("|")
        val restored = GameEngine(27L)
        assertTrue(restored.importState(legacyV2))
        assertEquals("DAILY", restored.mode)
        assertEquals(source.tray.map { it?.id }, restored.tray.map { it?.id })
    }

    @Test fun versionOneSaveMigratesWithoutBreakingTheCurrentGame() {
        val source = GameEngine(28L); source.start("CLASSIC")
        val legacyV1 = source.exportState().split('|').take(10).toMutableList().apply { this[0] = "1" }.joinToString("|")
        val restored = GameEngine(29L)
        assertTrue(restored.importState(legacyV1))
        assertEquals("CLASSIC", restored.mode)
        assertEquals(source.tray.map { it?.id }, restored.tray.map { it?.id })
    }

    @Test fun unsupportedPieceShapeIsRejected() {
        val source = GameEngine(30L); source.start()
        val fields = source.exportState().split('|').toMutableList()
        val pieces = fields[7].split(';').toMutableList()
        val p = pieces[0].split(':').toMutableList()
        p[1] = "0,0.2,2" // A non-catalogue shape.
        pieces[0] = p.joinToString(":")
        fields[7] = pieces.joinToString(";")
        assertFalse(GameEngine(31L).importState(fields.joinToString("|")))
    }

    @Test fun impossibleScoreForMoveCountIsRejected() {
        val game = GameEngine(32L); game.start()
        val fields = game.exportState().split('|').toMutableList()
        fields[2] = Int.MAX_VALUE.toString(); fields[3] = Int.MAX_VALUE.toString()
        assertFalse(GameEngine(33L).importState(fields.joinToString("|")))
    }

    @Test fun zeroRandomStateIsRejected() {
        val source = GameEngine(41L); source.start()
        val fields = source.exportState().split('|').toMutableList()
        fields[15] = "0"
        assertFalse(GameEngine(42L).importState(fields.joinToString("|")))
    }

    @Test fun oversizedSaveIsRejectedWithoutChangingLiveState() {
        val target = GameEngine(43L); target.start()
        val before = target.exportState()
        assertFalse(target.importState("x".repeat(16_385)))
        assertEquals(before, target.exportState())
    }

    @Test fun randomPlaySaveRestoreAndUndoRemainDeterministic() {
        for (seed in 1L..24L) {
            val game = GameEngine(seed); game.start("CLASSIC")
            repeat(60) { turn ->
                if (game.gameOver) game.start("CLASSIC", seed + turn + 1000L)
                val move = game.hint() ?: return@repeat
                assertTrue(game.place(move.first, move.second.x, move.second.y))
                val payload = game.exportState()
                val restored = GameEngine(seed xor 0x55AA55AAL)
                assertTrue(restored.importState(payload))
                assertEquals(payload, restored.exportState())
                if (turn % 9 == 0 && game.undo()) {
                    val undoPayload = game.exportState()
                    assertTrue(restored.importState(undoPayload))
                    assertEquals(undoPayload, restored.exportState())
                }
            }
        }
    }

    @Test fun invalidPieceIdAndNextIdRelationshipIsRejected() {
        val source = GameEngine(44L); source.start()
        val fields = source.exportState().split('|').toMutableList()
        fields[8] = "1"
        assertFalse(GameEngine(45L).importState(fields.joinToString("|")))
    }

    @Test fun newModeFallbackRemainsValid() {
        val game = GameEngine(46L)
        game.start("NOT_A_MODE")
        assertEquals("CLASSIC", game.mode)
        assertEquals(3, game.tray.size)
        assertFalse(game.gameOver)
    }

}
