package com.example.urlhud;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Owns the binary split-pane tree of WebViews and keeps the on-screen
 * LinearLayout hierarchy in sync with it. This is the native equivalent of
 * the recursive renderLeaf()/renderSplit() layout logic in index.html,
 * rebuilt with real Android views instead of DOM nodes since each pane is
 * a genuine top-level WebView (see MainActivity's class doc for why).
 *
 * Tree shape:
 *   Node.leaf(webView)                                  -- a single pane
 *   Node.split(direction, first, second, ratio)          -- two children plus
 *                                                            a draggable divider
 *
 * "row"  -> children side by side (horizontal LinearLayout)
 * "col"  -> children stacked (vertical LinearLayout)
 *
 * Each split node's on-screen LinearLayout has exactly three children in
 * order: [firstView, dividerView, secondView]. The divider is a fixed-size
 * drag handle; firstView/secondView split the remaining space using
 * LinearLayout weights that track node.ratio (first gets `ratio`, second
 * gets `1 - ratio`), so dragging the handle just adjusts those two weights
 * live instead of triggering a full re-render.
 */
public class PaneManager {

    public interface WebViewFactory {
        WebView create(String url);
    }

    /** Lets each pane's own +/- buttons trigger zoom without PaneManager needing to know how zoom levels are tracked. */
    public interface ZoomListener {
        void onZoomIn(WebView pane);
        void onZoomOut(WebView pane);
    }

    /**
     * Lets PaneManager read/write each individual pane's zoom level for
     * session persistence, without owning the zoom bookkeeping itself
     * (MainActivity's zoomLevels map remains the single source of truth).
     * Without this, session save/restore only knows about URLs and split
     * layout - zoom has to be threaded through separately per leaf, or
     * every restored pane ends up sharing one global zoom value instead of
     * remembering its own.
     */
    public interface ZoomIO {
        /** Current zoom level for this pane, for writing into the serialized tree. */
        float getZoom(WebView pane);
        /** Called right after a leaf's WebView is created during restore(), to seed its own saved zoom before the page loads. */
        void setInitialZoom(WebView pane, float zoom);
    }

    private static final String DEFAULT_SPLIT_URL = "https://example.com";
    private static final float DEFAULT_RATIO = 0.5f;
    private static final float MIN_RATIO = 0.15f;
    private static final float MAX_RATIO = 0.85f;
    private static final int DIVIDER_THICKNESS_DP = 6;
    private static final int DIVIDER_LINE_DP = 1;
    private static final int DIVIDER_COLOR = 0x33FFFFFF;
    private static final int DIVIDER_COLOR_ACTIVE = 0xFF4A90E2;

    // Focus-border shown around whichever pane was last touched, only when
    // there's more than one pane on screen (a single pane is unambiguously
    // "focused" already, so the border would just be visual noise). Drawn
    // as a foreground overlay (see refreshFocusBorders()), not padding, so
    // it never insets the WebView - panes stay edge-to-edge either way.
    private static final int FOCUS_BORDER_DP = 2;
    private static final int FOCUS_BORDER_COLOR = 0x664A90E2;

    // Per-pane zoom control: a small +/- pair pinned to each pane's own
    // right edge, vertically centered. Unlike the focus border, this is
    // always visible (even with a single pane, even in fullscreen) since
    // it's the only way to zoom now that the bar's zoom buttons are gone.
    private static final int ZOOM_CONTROL_WIDTH_DP = 28;
    private static final int ZOOM_CONTROL_BUTTON_HEIGHT_DP = 28;
    private static final int ZOOM_CONTROL_MARGIN_DP = 6;
    private static final int ZOOM_CONTROL_CORNER_RADIUS_DP = 6;
    private static final int ZOOM_CONTROL_BG_COLOR = 0xCC18181A;
    private static final int ZOOM_CONTROL_BORDER_COLOR = 0x33FFFFFF;
    private static final int ZOOM_CONTROL_TEXT_COLOR = 0xFFF2F2F2;

    private static class Node {
        boolean leaf;
        WebView webView;      // set when leaf
        String direction;     // "row" | "col", set when split
        float ratio = DEFAULT_RATIO; // share of space given to `first`
        Node first;
        Node second;
        Node parent;

        static Node newLeaf(WebView wv) {
            Node n = new Node();
            n.leaf = true;
            n.webView = wv;
            return n;
        }

        static Node newSplit(String direction, Node first, Node second, float ratio) {
            Node n = new Node();
            n.leaf = false;
            n.direction = direction;
            n.ratio = ratio;
            n.first = first;
            n.second = second;
            first.parent = n;
            second.parent = n;
            return n;
        }
    }

    private final Context context;
    private final FrameLayout container;
    private final WebViewFactory factory;
    private final ZoomListener zoomListener;
    private final ZoomIO zoomIO;
    private final float density;
    private Node root;
    private WebView activePane;

    // Rebuilt every render() call: maps each leaf's WebView to the FrameLayout
    // wrapping it, which is what actually paints the focus border (the
    // border lives in the wrapper's padding, not on the WebView itself).
    private final Map<WebView, FrameLayout> leafWrappers = new HashMap<>();

    public PaneManager(Context context, FrameLayout container, WebViewFactory factory, ZoomListener zoomListener, ZoomIO zoomIO) {
        this.context = context;
        this.container = container;
        this.factory = factory;
        this.zoomListener = zoomListener;
        this.zoomIO = zoomIO;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    /** Creates a fresh single-pane tree and renders it. Returns the new WebView. */
    public WebView init(String url) {
        WebView wv = factory.create(url);
        root = Node.newLeaf(wv);
        render();
        return wv;
    }

    /** Rebuilds the tree (and views) from a serialized JSON tree. Returns a leaf WebView to make active, or null on failure. */
    public WebView restore(JSONObject tree) {
        try {
            root = buildFromJson(tree);
        } catch (JSONException e) {
            root = null;
            return null;
        }
        if (root == null) return null;
        render();
        return firstLeaf(root);
    }

    private Node buildFromJson(JSONObject o) throws JSONException {
        String type = o.optString("type", "leaf");
        if ("split".equals(type)) {
            String direction = o.optString("direction", "row");
            float ratio = (float) o.optDouble("ratio", DEFAULT_RATIO);
            Node first = buildFromJson(o.getJSONObject("first"));
            Node second = buildFromJson(o.getJSONObject("second"));
            if (first == null || second == null) return null;
            return Node.newSplit(direction, first, second, clampRatio(ratio));
        } else {
            String url = o.optString("url", DEFAULT_SPLIT_URL);
            WebView wv = factory.create(url);
            // Older saved sessions won't have a "zoom" key - in that case
            // leave whatever default the factory already seeded (the old,
            // shared-global-value behavior) rather than forcing 0.
            if (zoomIO != null && o.has("zoom")) {
                zoomIO.setInitialZoom(wv, (float) o.optDouble("zoom", 0));
            }
            return Node.newLeaf(wv);
        }
    }

    /** Splits the pane containing `pane` into two, adding a fresh pane alongside it. Returns the new WebView, or null if `pane` isn't found. */
    public WebView splitPane(WebView pane, String direction) {
        Node leaf = findLeaf(root, pane);
        if (leaf == null) return null;

        WebView newWebView = factory.create(DEFAULT_SPLIT_URL);
        Node newLeaf = Node.newLeaf(newWebView);
        Node originalLeaf = Node.newLeaf(pane);
        Node split = Node.newSplit("col".equals(direction) ? "col" : "row", originalLeaf, newLeaf, DEFAULT_RATIO);

        if (leaf.parent == null) {
            root = split;
        } else {
            Node parent = leaf.parent;
            if (parent.first == leaf) parent.first = split; else parent.second = split;
            split.parent = parent;
        }

        render();
        return newWebView;
    }

    /** Removes the pane containing `pane`, collapsing its parent split. Returns the WebView that should become active, or null if there's nothing left to close. */
    public WebView closePane(WebView pane) {
        Node leaf = findLeaf(root, pane);
        if (leaf == null || leaf.parent == null) return null; // can't close the last remaining pane

        Node parent = leaf.parent;
        Node sibling = (parent.first == leaf) ? parent.second : parent.first;
        sibling.parent = parent.parent;

        if (parent.parent == null) {
            root = sibling;
        } else {
            Node grandparent = parent.parent;
            if (grandparent.first == parent) grandparent.first = sibling; else grandparent.second = sibling;
        }

        // WebView.destroy() must be called only after the view has been
        // removed from the view hierarchy - destroying it while it's still
        // attached (which render() would otherwise do a moment later via
        // removeAllViews()) is what causes the closed pane's surface to
        // stick around blank/stale on some WebView builds, sometimes even
        // bleeding into the sibling's next layout pass. Detach explicitly
        // first so destroy() always runs on an already-orphaned view.
        detachFromParent(pane);
        pane.destroy();
        render();
        return firstLeaf(sibling);
    }

    public void setActivePane(WebView pane) {
        this.activePane = pane;
        refreshFocusBorders();
    }

    /** Applies the subtle focus border to whichever leaf's wrapper matches activePane. Drawn as a foreground overlay so it never insets the WebView underneath. */
    private void refreshFocusBorders() {
        boolean showBorders = hasSplit(); // no point bordering a single, unambiguous pane
        for (Map.Entry<WebView, FrameLayout> entry : leafWrappers.entrySet()) {
            boolean isActive = showBorders && entry.getKey() == activePane;
            entry.getValue().setForeground(isActive ? focusBorderDrawable() : null);
        }
    }

    private GradientDrawable focusBorderDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dpToPx(FOCUS_BORDER_DP), FOCUS_BORDER_COLOR);
        return d;
    }

    public boolean hasSplit() {
        return root != null && !root.leaf;
    }

    /** Serializes the current tree, storing each leaf's last-known URL via its View tag, and each split's current divider ratio. */
    public JSONObject serialize() {
        if (root == null) return null;
        try {
            return serializeNode(root);
        } catch (JSONException e) {
            return null;
        }
    }

    private JSONObject serializeNode(Node node) throws JSONException {
        JSONObject o = new JSONObject();
        if (node.leaf) {
            o.put("type", "leaf");
            Object tag = node.webView.getTag();
            o.put("url", tag != null ? tag.toString() : DEFAULT_SPLIT_URL);
            if (zoomIO != null) o.put("zoom", zoomIO.getZoom(node.webView));
        } else {
            o.put("type", "split");
            o.put("direction", node.direction);
            o.put("ratio", node.ratio);
            o.put("first", serializeNode(node.first));
            o.put("second", serializeNode(node.second));
        }
        return o;
    }

    private Node findLeaf(Node node, WebView pane) {
        if (node == null) return null;
        if (node.leaf) return node.webView == pane ? node : null;
        Node found = findLeaf(node.first, pane);
        return found != null ? found : findLeaf(node.second, pane);
    }

    private WebView firstLeaf(Node node) {
        if (node == null) return null;
        return node.leaf ? node.webView : firstLeaf(node.first);
    }

    private static float clampRatio(float ratio) {
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
    }

    // -------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------

    /** Rebuilds the on-screen view hierarchy under `container` to match the current tree. */
    private void render() {
        container.removeAllViews();
        leafWrappers.clear();
        if (root == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.addView(buildView(root), lp);
        refreshFocusBorders();
    }

    private View buildView(Node node) {
        if (node.leaf) {
            detachFromParent(node.webView);
            return wrapLeaf(node.webView);
        }

        boolean horizontal = !"col".equals(node.direction);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View firstView = buildView(node.first);
        View secondView = buildView(node.second);
        View divider = createDivider(layout, node, horizontal);

        LinearLayout.LayoutParams firstLp = horizontal
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, node.ratio)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, node.ratio);
        LinearLayout.LayoutParams dividerLp = horizontal
                ? new LinearLayout.LayoutParams(dpToPx(DIVIDER_THICKNESS_DP), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(DIVIDER_THICKNESS_DP));
        LinearLayout.LayoutParams secondLp = horizontal
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - node.ratio)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f - node.ratio);

        layout.addView(firstView, firstLp);
        layout.addView(divider, dividerLp);
        layout.addView(secondView, secondLp);
        return layout;
    }

    /**
     * Wraps a leaf's WebView in a FrameLayout with no padding, so the
     * WebView always fills the pane edge-to-edge - the only thing
     * separating two split panes is the thin drag divider between them.
     * The focus-border ring (see refreshFocusBorders()) is painted as a
     * foreground overlay on top of this wrapper instead, so it never eats
     * into that space either. Also anchors that pane's own zoom control to
     * the wrapper's right edge, vertically centered, so it stays pinned to
     * this specific pane through re-layouts.
     */
    private FrameLayout wrapLeaf(WebView webView) {
        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.setBackgroundColor(Color.TRANSPARENT);
        wrapper.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zoomLp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        zoomLp.setMargins(0, 0, dpToPx(ZOOM_CONTROL_MARGIN_DP), 0);
        View zoomControl = createZoomControl(webView);
        wrapper.addView(zoomControl, zoomLp);
        // Belt-and-suspenders: guarantees the zoom control is the topmost
        // child for both drawing AND touch dispatch, in case the WebView's
        // own hardware layer ever won a Z-order tie against a same-index
        // sibling (the class of bug where a button overlapping a WebView
        // renders fine but silently never receives taps).
        wrapper.bringChildToFront(zoomControl);

        leafWrappers.put(webView, wrapper);
        return wrapper;
    }

    /** Builds the persistent +/- zoom control pinned to one pane's right edge, vertically centered. */
    private View createZoomControl(WebView pane) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(zoomControlBackground());

        int buttonSize = dpToPx(ZOOM_CONTROL_BUTTON_HEIGHT_DP);
        int width = dpToPx(ZOOM_CONTROL_WIDTH_DP);

        View zoomIn = zoomButton("+", pane, true);
        View divider = new View(context);
        divider.setBackgroundColor(ZOOM_CONTROL_BORDER_COLOR);
        View zoomOut = zoomButton("\u2212", pane, false);

        group.addView(zoomIn, new LinearLayout.LayoutParams(width, buttonSize));
        group.addView(divider, new LinearLayout.LayoutParams(width, dpToPx(1)));
        group.addView(zoomOut, new LinearLayout.LayoutParams(width, buttonSize));
        return group;
    }

    private View zoomButton(String label, WebView pane, boolean isZoomIn) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextColor(ZOOM_CONTROL_TEXT_COLOR);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);

        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        if (outValue.resourceId != 0) button.setBackgroundResource(outValue.resourceId);

        button.setOnClickListener(v -> {
            if (zoomListener == null) return;
            if (isZoomIn) zoomListener.onZoomIn(pane); else zoomListener.onZoomOut(pane);
        });

        // Explicitly claim the whole touch gesture on ACTION_DOWN and fire
        // the click ourselves on ACTION_UP, instead of trusting the default
        // click detection inside View#onTouchEvent to win the gesture
        // against the WebView sitting directly underneath. Without this, a
        // DOWN event that isn't unambiguously consumed here can end up
        // handled by the WebView instead, and the button never sees the
        // matching UP - it looks like a normal button but silently never
        // registers a tap.
        button.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (event.getX() >= 0 && event.getX() <= v.getWidth()
                            && event.getY() >= 0 && event.getY() <= v.getHeight()) {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });

        return button;
    }

    private GradientDrawable zoomControlBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(ZOOM_CONTROL_BG_COLOR);
        bg.setStroke(dpToPx(1), ZOOM_CONTROL_BORDER_COLOR);
        bg.setCornerRadius(dpToPx(ZOOM_CONTROL_CORNER_RADIUS_DP));
        return bg;
    }

    /**
     * Builds the drag handle sitting between a split's two children. The
     * touch target is the full DIVIDER_THICKNESS_DP wide/tall, but only a
     * thin centered line is actually painted so it reads as a hairline
     * rather than a fat gray bar, matching the desktop split-line look.
     */
    private View createDivider(LinearLayout parent, Node node, boolean horizontal) {
        FrameLayout handle = new FrameLayout(context);
        handle.setBackgroundColor(Color.TRANSPARENT);

        View line = new View(context);
        line.setBackgroundColor(DIVIDER_COLOR);
        FrameLayout.LayoutParams lineLp = horizontal
                ? new FrameLayout.LayoutParams(dpToPx(DIVIDER_LINE_DP), ViewGroup.LayoutParams.MATCH_PARENT)
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(DIVIDER_LINE_DP));
        lineLp.gravity = android.view.Gravity.CENTER;
        handle.addView(line, lineLp);

        handle.setOnTouchListener(new View.OnTouchListener() {
            float startTouchPos;
            float startRatio;
            int containerSize;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startTouchPos = horizontal ? event.getRawX() : event.getRawY();
                        startRatio = node.ratio;
                        containerSize = horizontal ? parent.getWidth() : parent.getHeight();
                        line.setBackgroundColor(DIVIDER_COLOR_ACTIVE);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (containerSize <= 0) return true;
                        float currentTouchPos = horizontal ? event.getRawX() : event.getRawY();
                        float deltaRatio = (currentTouchPos - startTouchPos) / containerSize;
                        node.ratio = clampRatio(startRatio + deltaRatio);
                        applyRatio(parent, node);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        line.setBackgroundColor(DIVIDER_COLOR);
                        return true;
                    default:
                        return false;
                }
            }
        });

        return handle;
    }

    /** Applies node.ratio to the first/second children's existing LinearLayout weights without rebuilding the tree. */
    private void applyRatio(LinearLayout parent, Node node) {
        View firstView = parent.getChildAt(0);
        View secondView = parent.getChildAt(2);
        if (firstView == null || secondView == null) return;

        LinearLayout.LayoutParams firstLp = (LinearLayout.LayoutParams) firstView.getLayoutParams();
        LinearLayout.LayoutParams secondLp = (LinearLayout.LayoutParams) secondView.getLayoutParams();
        firstLp.weight = node.ratio;
        secondLp.weight = 1f - node.ratio;
        firstView.setLayoutParams(firstLp);
        secondView.setLayoutParams(secondLp);
    }

    private void detachFromParent(View view) {
        if (view == null) return;
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) parent.removeView(view);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * density);
    }
}
