package com.adlesstv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Favorites and installed apps share one scrolling, recycled grid. */
final class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Listener {
        void focused(AppRepository.App app);
        void opened(AppRepository.App app);
        void options(AppRepository.App app);
        boolean navigate(int position, int key);
    }

    private static final class Entry {
        final AppRepository.App app;
        final int heading;
        final int artworkRevision;
        Entry(AppRepository.App app, int heading, int artworkRevision) {
            this.app = app; this.heading = heading; this.artworkRevision = artworkRevision;
        }
        String key() { return app == null ? "heading:" + heading : app.key(); }
    }

    private final List<Entry> items = new ArrayList<>();
    private final Map<String, Long> stableIds = new HashMap<>();
    private long nextId = 1;
    private final Context context;
    private final Listener listener;
    private final ArtworkLoader artwork;
    private final boolean dock;
    private int dockWidth;
    private int columns = 4;
    private boolean reducedMotion;
    private GridNavigation navigation = new GridNavigation(new boolean[0], 4);

    AppAdapter(Context context, Listener listener, ArtworkLoader artwork, boolean dock) {
        this.context = context;
        this.listener = listener;
        this.artwork = artwork;
        this.dock = dock;
        dockWidth = dp(context, context.getResources().getDisplayMetrics().heightPixels
                < dp(context, 500) ? 148 : 190);
        setHasStableIds(true);
    }

    void replace(List<AppRepository.App> favorites, List<AppRepository.App> apps) {
        List<Entry> next = new ArrayList<>();
        if (!favorites.isEmpty()) {
            if (!dock) next.add(new Entry(null, R.string.favorites, 0));
            for (AppRepository.App app : favorites) next.add(new Entry(app, 0, artwork.revision()));
        }
        if (!apps.isEmpty()) {
            next.add(new Entry(null, R.string.all_apps, 0));
            for (AppRepository.App app : apps) next.add(new Entry(app, 0, artwork.revision()));
        }
        List<Entry> previous = new ArrayList<>(items);
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).key().equals(next.get(newPosition).key());
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                AppRepository.App old = previous.get(oldPosition).app;
                AppRepository.App now = next.get(newPosition).app;
                if (old == null || now == null) return old == now;
                return previous.get(oldPosition).artworkRevision == next.get(newPosition).artworkRevision
                        && old.label.equals(now.label) && Objects.equals(
                        old.info.activityInfo.applicationInfo.sourceDir,
                        now.info.activityInfo.applicationInfo.sourceDir);
            }
        });
        items.clear();
        items.addAll(next);
        updateNavigation();
        changes.dispatchUpdatesTo(this);
    }

    void setDockWidth(int width) {
        if (!dock || dockWidth == width) return;
        dockWidth = width;
        notifyItemRangeChanged(0, getItemCount());
    }

    void setColumns(int columns) { this.columns = columns; updateNavigation(); }
    void setReducedMotion(boolean enabled) { reducedMotion = enabled; }
    private void updateNavigation() {
        boolean[] headings = new boolean[items.size()];
        for (int i = 0; i < headings.length; i++) headings[i] = isHeading(i);
        navigation = new GridNavigation(headings, columns);
    }
    int move(int from, GridNavigation.Direction direction) { return navigation.move(from, direction); }
    boolean isHeading(int position) { return items.get(position).app == null; }
    int firstApp() {
        for (int i = 0; i < items.size(); i++) if (!isHeading(i)) return i;
        return RecyclerView.NO_POSITION;
    }
    AppRepository.App at(int position) { return items.get(position).app; }
    int indexOf(String component) {
        if (component != null) for (int i = 0; i < items.size(); i++) {
            if (component.equals(items.get(i).key())) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    @Override public long getItemId(int position) {
        String key = items.get(position).key();
        Long id = stableIds.get(key);
        if (id == null) { id = nextId++; stableIds.put(key, id); }
        return id;
    }
    @Override public int getItemCount() { return items.size(); }
    @Override public int getItemViewType(int position) { return isHeading(position) ? 0 : 1; }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == 0) {
            TextView label = new TextView(context);
            label.setTextColor(Color.rgb(162, 169, 182));
            label.setTextSize(16);
            label.setPadding(dp(context, 9), dp(context, 14), dp(context, 9), dp(context, 8));
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(label) {};
        }
        Tile tile = new Tile(context);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                dock ? dockWidth : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = dp(context, dock ? 5 : 7);
        params.setMargins(margin, margin, margin, margin);
        tile.setLayoutParams(params);
        return new Holder(tile);
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder base, int position) {
        Entry entry = items.get(position);
        if (entry.app == null) { ((TextView) base.itemView).setText(entry.heading); return; }
        Holder holder = (Holder) base;
        AppRepository.App app = entry.app;
        if (dock) holder.tile.getLayoutParams().width = dockWidth;
        holder.tile.label.setText(app.label);
        holder.tile.setContentDescription(app.label);
        artwork.bind(holder.tile.icon, app, holder.tile::setAccent);
        holder.tile.setOnClickListener(v -> listener.opened(app));
        holder.tile.setOnLongClickListener(v -> { listener.options(app); return true; });
        holder.tile.animate().cancel();
        holder.tile.setScaleX(holder.tile.isFocused() && !reducedMotion ? 1.05f : 1f);
        holder.tile.setScaleY(holder.tile.isFocused() && !reducedMotion ? 1.05f : 1f);
        holder.tile.setOnFocusChangeListener((v, focused) -> {
            animateFocus(v, focused, reducedMotion);
            if (focused) listener.focused(app);
        });
        holder.tile.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int index = holder.getBindingAdapterPosition();
            if (index == RecyclerView.NO_POSITION) return false;
            if (key == KeyEvent.KEYCODE_MENU) { listener.options(app); return true; }
            return listener.navigate(index, key);
        });
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof Holder) {
            Tile tile = ((Holder) holder).tile;
            artwork.cancel(tile.icon);
            tile.icon.setImageDrawable(null);
            tile.animate().cancel();
            tile.setScaleX(1f);
            tile.setScaleY(1f);
        }
        super.onViewRecycled(holder);
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static void animateFocus(View view, boolean focused, boolean reducedMotion) {
        view.animate().cancel();
        float scale = focused ? 1.05f : 1f;
        if (reducedMotion) {
            view.setScaleX(1f);
            view.setScaleY(1f);
        } else view.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
    }

    /** Native 16:9 TV art, rounded focus surface and a separate readable app name. */
    static final class Tile extends FrameLayout {
        final ImageView icon;
        final TextView label;
        private final GradientDrawable normal = new GradientDrawable();
        private final GradientDrawable focused = new GradientDrawable();
        private int accent = Integer.MIN_VALUE;
        Tile(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            normal.setCornerRadius(dp(context, 16));
            focused.setCornerRadius(dp(context, 16));
            normal.setOrientation(GradientDrawable.Orientation.TL_BR);
            focused.setOrientation(GradientDrawable.Orientation.TL_BR);
            focused.setStroke(dp(context, 2), Color.WHITE);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, focused);
            states.addState(new int[0], normal);
            FrameLayout card = new FrameLayout(context);
            card.setId(View.generateViewId());
            card.setDuplicateParentStateEnabled(true);
            card.setBackground(states);
            card.setClipToOutline(true);
            addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setAccent(0);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(icon, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            // Draw the focus stroke above the artwork, which would otherwise cover it.
            GradientDrawable ring = new GradientDrawable();
            ring.setColor(Color.TRANSPARENT);
            ring.setCornerRadius(dp(context, 16));
            ring.setStroke(dp(context, 2), Color.WHITE);
            StateListDrawable foreground = new StateListDrawable();
            foreground.addState(new int[]{android.R.attr.state_focused}, ring);
            foreground.addState(new int[0], new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            card.setForeground(foreground);
            label = new TextView(context);
            label.setTextColor(Color.rgb(224, 227, 234));
            label.setTextSize(14);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setIncludeFontPadding(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }

        void setAccent(int color) {
            if (accent == color) return;
            accent = color;
            int hue = color == 0 ? 0xFF586273 : color;
            int[] colors = {blend(hue, 0xFF252B34, 75), blend(hue, 0xFF10141B, 38)};
            normal.setColors(colors);
            focused.setColors(colors);
        }
        private static int blend(int color, int base, int percent) {
            return Color.rgb((Color.red(color) * percent + Color.red(base) * (100 - percent)) / 100,
                    (Color.green(color) * percent + Color.green(base) * (100 - percent)) / 100,
                    (Color.blue(color) * percent + Color.blue(base) * (100 - percent)) / 100);
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int artHeight = Math.round(width * 9f / 16f);
            View card = getChildAt(0);
            card.getLayoutParams().height = artHeight;
            int height = artHeight + Math.round(label.getTextSize() * 1.35f) + dp(getContext(), 10);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final Tile tile;
        Holder(Tile tile) { super(tile); this.tile = tile; }
    }
}
