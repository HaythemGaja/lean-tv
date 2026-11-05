package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The tiles inside one folder: the folders it holds first, then its apps. Sized and drawn like Home's tiles
 * (item_app.xml, {@link AppAdapter#tileWidthPx}), with a lock badge on anything the PIN guards.
 */
final class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.Holder> {

    interface Listener {
        void onOpen(Entry entry);

        /** Hold OK (or the remote's menu key): the folder's own menu for this tile. */
        void onMenu(Entry entry, View card);
    }

    static final int APP = 0, FOLDER = 1;

    static final class Entry {
        final int kind;
        final String label;
        final String pkg;
        final String folderId;
        final android.content.ComponentName component;
        /** The app's banner, or null when it has none and its icon is drawn instead. */
        final Drawable banner;
        final Drawable icon;
        final boolean leanback;
        boolean locked;
        /** FOLDER tiles: the first four app icons it holds, drawn as its cover; null when it holds none. */
        Drawable[] cover;

        Entry(int kind, String label, String pkg, String folderId, android.content.ComponentName component,
              Drawable banner, Drawable icon, boolean leanback, boolean locked) {
            this.kind = kind;
            this.label = label;
            this.pkg = pkg;
            this.folderId = folderId;
            this.component = component;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
            this.locked = locked;
        }

        static Entry app(PackageManager pm, String label, ResolveInfo ri, Drawable banner, boolean leanback,
                         boolean locked) {
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            return new Entry(APP, label, ri.activityInfo.packageName, null,
                    new android.content.ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    banner, icon, leanback, locked);
        }

        static Entry folder(String id, String name, Drawable icon, boolean locked) {
            return new Entry(FOLDER, name, "", id, null, null, icon, false, locked);
        }
    }

    /** Alpha of a tile's name while it is not focused: always readable here, unlike Home's dock. */
    private static final float REST_NAME_ALPHA = 0.78f;

    private final Context ctx;
    private final Listener listener;
    private final int columns;
    private final boolean animations;
    private final boolean reducedMotion;
    private final List<Entry> entries = new ArrayList<>();

    FolderAdapter(Context ctx, Listener listener, int columns, boolean animations, boolean reducedMotion) {
        this.ctx = ctx;
        this.listener = listener;
        this.columns = Math.max(1, columns);
        this.animations = animations;
        this.reducedMotion = reducedMotion;
    }

    void setEntries(List<Entry> next) {
        entries.clear();
        entries.addAll(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false);
        float density = ctx.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = Math.max(0, width - parent.getPaddingLeft() - parent.getPaddingRight());
        int w = AppAdapter.tileWidthPx(inner, columns, density), h = AppAdapter.tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(4 * density);
        nameLp.height = Math.round(20 * density);
        name.setLayoutParams(nameLp);
        int pad = Math.round(4 * density);
        v.setPadding(v.getPaddingLeft(), pad, v.getPaddingRight(), pad);
        return new Holder(v, listener, animations, reducedMotion, density);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = entries.get(position);
        h.entry = e;
        h.name.setText(e.label);
        h.name.setVisibility(View.VISIBLE);
        h.badge.setVisibility(e.locked ? View.VISIBLE : View.GONE);
        if (e.kind == FOLDER) {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        // A folder tile shows the first four apps it holds as a cover instead of the folder mark.
        boolean cover = e.kind == FOLDER && e.cover != null;
        h.cover.setVisibility(cover ? View.VISIBLE : View.GONE);
        if (cover) {
            h.icon.setVisibility(View.GONE);
            for (int i = 0; i < 4; i++) {
                h.covers[i].setImageDrawable(e.cover[i]);
                h.covers[i].setVisibility(e.cover[i] == null ? View.INVISIBLE : View.VISIBLE);
            }
        }
        boolean focused = h.card.isFocused();
        h.name.setAlpha(focused ? 1f : REST_NAME_ALPHA);
        h.card.setOnClickListener(v -> listener.onOpen(e));
        h.card.setOnLongClickListener(v -> {
            listener.onMenu(e, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    /** A banner for the tile, or null when the app has none or ships a square image that would blur. */
    static Drawable banner(Context ctx, PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                banner = resources.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        return banner != null && AppAdapter.isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight())
                ? banner : null;
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner, icon, badge;
        final View cover;
        final ImageView[] covers = new ImageView[4];
        final TextView label, name;
        Entry entry;

        Holder(View v, Listener listener, boolean animations, boolean reducedMotion, float density) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            badge = v.findViewById(R.id.badge);
            cover = v.findViewById(R.id.cover);
            ImageView[] slots = {v.findViewById(R.id.cover0), v.findViewById(R.id.cover1),
                    v.findViewById(R.id.cover2), v.findViewById(R.id.cover3)};
            System.arraycopy(slots, 0, covers, 0, covers.length);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                name.animate().cancel();
                float scale = focused && animations ? 1.08f : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    name.setAlpha(focused ? 1f : REST_NAME_ALPHA);
                } else if (reducedMotion) {
                    view.setScaleX(scale);
                    view.setScaleY(scale);
                    name.setAlpha(focused ? 1f : REST_NAME_ALPHA);
                } else {
                    view.animate().scaleX(scale).scaleY(scale).setDuration(focused ? 190 : 140).start();
                    name.animate().alpha(focused ? 1f : REST_NAME_ALPHA).setDuration(160).start();
                }
            });
            card.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_MENU || event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (entry != null) listener.onMenu(entry, view);
                return true;
            });
        }
    }
}
