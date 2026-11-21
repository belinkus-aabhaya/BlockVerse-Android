package com.blockverse.game

fun main() {
    val game = GameEngine(4L); game.start()
    check(game.board.all { row -> row.all { it == 0 } })
    check(game.tray.size == 3)
    val piece = game.tray[0]!!
    check(!game.place(0, 8, 8))
    check(game.tray[0]!!.id == piece.id)

    val daily = GameEngine(12L); daily.start("DAILY", 20261121L)
    val hint = daily.hint()!!
    check(daily.place(hint.first, hint.second.x, hint.second.y))
    val restored = GameEngine(99L)
    check(restored.importState(daily.exportState()))
    check(restored.exportState() == daily.exportState())

    val invalid = daily.exportState().split('|').toMutableList().apply { this[5] = "1" }.joinToString("|")
    check(!GameEngine(3L).importState(invalid))

    val badColor = GameEngine(8L); badColor.start(); badColor.board[0][0] = 999999
    check(!GameEngine(9L).importState(badColor.exportState()))

    println("PASS: deterministic state, placement validation, corrupt-save rejection, palette validation")
}
