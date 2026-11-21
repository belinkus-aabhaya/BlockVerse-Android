package com.blockverse.game

import android.app.Activity
import android.os.Bundle

class MainActivity : Activity() {
    private lateinit var store: LocalStore
    private lateinit var gameView: GameView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalStore(this)
        gameView = GameView(this, store)
        setContentView(gameView)
        gameView.applySystemBarAppearance()
        gameView.setOnApplyWindowInsetsListener { _, insets ->
            gameView.setSystemInsets(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
        gameView.requestApplyInsets()
    }
    override fun onResume() { super.onResume(); if (::gameView.isInitialized) gameView.resumeTimer() }
    override fun onPause() {
        if (::gameView.isInitialized) { gameView.pauseTimer(); gameView.persist() }
        super.onPause()
    }
    override fun onDestroy() { if (::gameView.isInitialized) gameView.dispose(); if (::store.isInitialized) store.close(); super.onDestroy() }
    override fun onBackPressed() {
        if (::gameView.isInitialized && gameView.goHome()) return
        super.onBackPressed()
    }
}
