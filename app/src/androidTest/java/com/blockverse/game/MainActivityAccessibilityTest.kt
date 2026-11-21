package com.blockverse.game

import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Smoke-tests the custom virtual accessibility tree on a real Android framework. */
class MainActivityAccessibilityTest : ActivityInstrumentationTestCase2<MainActivity>(MainActivity::class.java) {
    fun testHomeControlsAndGameBoardAreExposedAsVirtualNodes() {
        val activity = activity
        val content = activity.findViewById<View>(android.R.id.content) as android.view.ViewGroup
        val gameView = content.getChildAt(0) as GameView
        runTestOnUiThread {
            val provider = gameView.accessibilityNodeProvider
                ?: throw AssertionError("Accessibility provider missing")
            val root = provider.createAccessibilityNodeInfo(View.NO_ID)
                ?: throw AssertionError("Accessibility root missing")
            assertTrue(root.childCount >= 9)
            assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertNotNull(provider.createAccessibilityNodeInfo(11))
            val firstBoardCell = provider.createAccessibilityNodeInfo(100)
                ?: throw AssertionError("Board cell missing from accessibility tree")
            assertTrue(firstBoardCell.isFocusable)
        }
    }
}
