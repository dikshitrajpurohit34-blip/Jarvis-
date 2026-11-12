package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.IdentityHashMap

/**
 * JARVIS accessibility service.
 *
 * Provides public, documented operations used by [ActionExecutor]:
 *   - [globalAction] (and its [performBack]/[performHome]/[performRecents]
 *     convenience wrappers) via AccessibilityService.GLOBAL_ACTION_*.
 *   - [scroll] via AccessibilityNodeInfo.ACTION_SCROLL_FORWARD/BACKWARD
 *     on a scrollable node, with a dispatchGesture swipe fallback.
 *   - [clickByText] via findAccessibilityNodeInfosByText + parent walk +
 *     ACTION_CLICK.
 *   - [enterText] via FOCUS_INPUT + ACTION_SET_TEXT.
 *
 * Node ownership:
 *   Every AccessibilityNodeInfo obtained from the framework
 *   (rootInActiveWindow, findAccessibilityNodeInfosByText, getChild,
 *   parent, findFocus) is owned by this service and must be recycled.
 *   Each entry point uses try/finally to ensure recycling on every return
 *   and exception path. Recycling is bounded and each node is recycled at
 *   most once (identity-based set where multiple nodes may be involved).
 *
 * recycle() is deprecated as of API 33 (no-op there) but remains
 * meaningful on API 29–32, so it is called and the deprecation warning is
 * suppressed.
 *
 * Lifecycle:
 *   - [instance] is set only in onServiceConnected().
 *   - [instance] is cleared in onUnbind() and onDestroy().
 *   - It is never assigned from an initializer or constructor.
 *
 * No GLOBAL_ACTION_SCROLL_DOWN or GLOBAL_ACTION_SCROLL_FORWARD constants
 * are used (they do not exist). Scrolling is delegated to node scroll
 * actions with a gesture fallback.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JARVIS"

        /** Maximum parent-walk depth from a candidate node. */
        private const val MAX_PARENT_DEPTH = 20

        /** Maximum recursive descent depth when searching for scrollable. */
        private const val MAX_SCROLL_DEPTH = 30

        /** Upper bound on distinct owned nodes accumulated by clickByText. */
        private const val MAX_OWNED_NODES = 200

        /**
         * Set in onServiceConnected(); cleared in onUnbind/onDestroy().
         * Never assigned from an initializer or constructor.
         */
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No reactive behavior; actions are driven by the session pipeline.
    }

    override fun onInterrupt() {
        Log.i(TAG, "AccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- Global navigation (no nodes involved) ----------

    /**
     * Perform a global navigation action. [action] must be one of the
     * documented AccessibilityService.GLOBAL_ACTION_* constants (e.g.
     * GLOBAL_ACTION_BACK, GLOBAL_ACTION_HOME, GLOBAL_ACTION_RECENTS).
     * Returns false for unknown/invalid actions or framework failures.
     */
    fun globalAction(action: Int): Boolean =
        try {
            performGlobalAction(action)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "performGlobalAction($action) failed: ${t.javaClass.simpleName}"
            )
            false
        }

    /** Convenience wrapper used by ActionExecutor for BACK. */
    fun performBack(): Boolean = globalAction(GLOBAL_ACTION_BACK)

    /** Convenience wrapper used by ActionExecutor for HOME. */
    fun performHome(): Boolean = globalAction(GLOBAL_ACTION_HOME)

    /** Convenience wrapper used by ActionExecutor for RECENTS. */
    fun performRecents(): Boolean = globalAction(GLOBAL_ACTION_RECENTS)

    // ---------- Scroll ----------

    fun scroll(forward: Boolean): Boolean {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null }
        if (root != null) {
            try {
                val action = if (forward)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (tryScrollRecursive(root, action, 0)) return true
            } finally {
                safeRecycle(root)
            }
        }
        return swipe(forward)
    }

    /**
     * Depth-first search for a scrollable node and perform [action] on it.
     * Every child obtained via getChild() is recycled in a finally block,
     * including on the success path. The caller owns and recycles [node].
     */
    private fun tryScrollRecursive(
        node: AccessibilityNodeInfo,
        action: Int,
        depth: Int
    ): Boolean {
        if (depth > MAX_SCROLL_DEPTH) return false

        if (node.isScrollable) {
            try {
                if (node.performAction(action)) return true
            } catch (t: Throwable) {
                Log.w(TAG, "Scroll on node failed: ${t.javaClass.simpleName}")
            }
        }

        val childCount = try { node.childCount } catch (t: Throwable) { 0 }
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (t: Throwable) { null }
                ?: continue
            try {
                if (tryScrollRecursive(child, action, depth + 1)) return true
            } finally {
                safeRecycle(child)
            }
        }
        return false
    }

    private fun swipe(forward: Boolean): Boolean {
        return try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val x = w / 2f
            val startY = h * if (forward) 0.7f else 0.3f
            val endY = h * if (forward) 0.3f else 0.7f

            val path = Path().apply {
                moveTo(x, startY)
                lineTo(x, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Swipe gesture failed: ${t.javaClass.simpleName}")
            false
        }
    }

    // ---------- Click by text ----------

    /**
     * Click the first node whose visible text contains [text] and whose
     * nearest clickable ancestor accepts ACTION_CLICK.
     *
     * Node ownership:
     *   - `root` and every node in the list returned by
     *     findAccessibilityNodeInfosByText() are owned by this method and
     *     are recycled exactly once.
     *   - Parents obtained during the parent walk are also owned and
     *     recycled exactly once.
     *   - Candidates that cannot be processed because [MAX_OWNED_NODES] is
     *     reached are recycled immediately (unless already owned), so no
     *     node returned by the framework is abandoned.
     *   - The identity-based set prevents double recycling when the same
     *     node object appears multiple times in the result list or as both
     *     a candidate and a parent.
     *
     * The search text is not logged (it may be sensitive user speech).
     */
    fun clickByText(text: String): Boolean {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null }
            ?: return false

        val owned: MutableSet<AccessibilityNodeInfo> =
            Collections.newSetFromMap(
                IdentityHashMap<AccessibilityNodeInfo, Boolean>()
            )
        owned.add(root)

        try {
            val candidates = try {
                root.findAccessibilityNodeInfosByText(text)
            } catch (t: Throwable) {
                // Do not log the search text; it may be sensitive.
                Log.w(
                    TAG,
                    "findAccessibilityNodeInfosByText failed: ${t.javaClass.simpleName}"
                )
                null
            }
            if (candidates.isNullOrEmpty()) return false

            for (candidate in candidates) {
                if (owned.size >= MAX_OWNED_NODES) {
                    // Budget exhausted: this candidate will not be processed.
                    // Recycle it now so every node returned by the framework
                    // is released, unless it is already owned (in which case
                    // the finally block owns its lifecycle).
                    if (!owned.contains(candidate)) {
                        safeRecycle(candidate)
                    }
                    continue
                }
                if (!owned.add(candidate)) continue

                var current: AccessibilityNodeInfo? = candidate
                var depth = 0
                while (current != null && !current.isClickable &&
                    depth < MAX_PARENT_DEPTH && owned.size < MAX_OWNED_NODES
                ) {
                    val parent = try { current.parent } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "parent walk failed: ${t.javaClass.simpleName}"
                        )
                        null
                    }
                    if (parent != null) owned.add(parent)
                    current = parent
                    depth++
                }

                if (current != null && current.isClickable) {
                    val ok = try {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "ACTION_CLICK failed: ${t.javaClass.simpleName}"
                        )
                        false
                    }
                    if (ok) return true
                }
            }
            return false
        } finally {
            for (node in owned) {
                safeRecycle(node)
            }
        }
    }

    // ---------- Enter text ----------

    /**
     * Set text on the currently focused input node.
     * The input string is not logged (it may be sensitive user speech).
     */
    fun enterText(input: String): Boolean {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null }
            ?: return false
        try {
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "findFocus(FOCUS_INPUT) failed: ${t.javaClass.simpleName}"
                )
                null
            } ?: return false

            try {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        input
                    )
                }
                return try {
                    focused.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        args
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "ACTION_SET_TEXT failed: ${t.javaClass.simpleName}"
                    )
                    false
                }
            } finally {
                safeRecycle(focused)
            }
        } finally {
            safeRecycle(root)
        }
    }

    // ---------- Node recycle helper ----------

    /**
     * Recycle [node], ignoring the IllegalStateException that older
     * framework versions throw if the node was already recycled.
     * On API 33+ recycle() is deprecated and is a no-op, so this call is
     * both safe and necessary for API 29–32.
     */
    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            node.recycle()
        } catch (t: Throwable) {
            // Already recycled or otherwise non-recyclable; ignore.
        }
    }
}
