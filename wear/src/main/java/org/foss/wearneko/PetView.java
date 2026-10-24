// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

/** Tiny ANeko sprite renderer. Its callbacks exist only while the service says the display is awake. */
public final class PetView extends View {
    private static final long WALK_FRAME_MS = 125L;
    private static final long GROOM_FRAME_MS = 250L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint();
    private final Rect destination = new Rect();
    private static final String[] MOVE_STATES = {"moveRight", "moveDownRight", "moveDown",
            "moveDownLeft", "moveLeft", "moveUpLeft", "moveUp", "moveUpRight"};
    private final WatchSkinPack pack;
    private final WatchSkinPack.Skin skin;
    private final float speed;
    private final Bitmap[][] walking = new Bitmap[8][2];
    private final Bitmap[] idle = new Bitmap[2];
    private final Bitmap[] grooming = new Bitmap[2];
    private final Bitmap[] napping = new Bitmap[2];
    private Bitmap awake;
    private PetMotion.Behavior behavior = PetMotion.Behavior.WALK;
    private int direction;
    private long activeElapsedMs;
    private long lastFrameMs;
    private long reactionUntilMs;
    private boolean running;
    private boolean disposed;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running || disposed) return;
            long now = SystemClock.uptimeMillis();
            activeElapsedMs += (long) (Math.min(1000L, Math.max(0L, now - lastFrameMs)) * speed);
            lastFrameMs = now;
            invalidate();
            handler.postDelayed(this, pack == null ? WALK_FRAME_MS : 50L);
        }
    };

    public PetView(Context context) {
        this(context, null, null, 1f);
    }

    PetView(Context context, WatchSkinPack pack, WatchSkinPack.Skin skin, float speed) {
        super(context);
        this.pack = pack;
        this.skin = skin;
        this.speed = speed;
        setBackgroundColor(Color.TRANSPARENT);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        // The order matches PetMotion's clockwise octants, starting at east.
        int[][] walkResources = {
            { R.drawable.neko_right1, R.drawable.neko_right2 },
            { R.drawable.neko_dwright1, R.drawable.neko_dwright2 },
            { R.drawable.neko_down1, R.drawable.neko_down2 },
            { R.drawable.neko_dwleft1, R.drawable.neko_dwleft2 },
            { R.drawable.neko_left1, R.drawable.neko_left2 },
            { R.drawable.neko_upleft1, R.drawable.neko_upleft2 },
            { R.drawable.neko_up1, R.drawable.neko_up2 },
            { R.drawable.neko_upright1, R.drawable.neko_upright2 }
        };
        for (int d = 0; d < walking.length; d++) {
            for (int f = 0; f < 2; f++) walking[d][f] = load(walkResources[d][f]);
        }
        idle[0] = load(R.drawable.neko_mati1);
        idle[1] = load(R.drawable.neko_mati2);
        grooming[0] = load(R.drawable.neko_kaki1);
        grooming[1] = load(R.drawable.neko_kaki2);
        napping[0] = load(R.drawable.neko_sleep1);
        napping[1] = load(R.drawable.neko_sleep2);
        awake = load(R.drawable.neko_awake);
        setContentDescription(skin == null ? "Little cat companion" : skin.name);
    }

    public void setPose(int direction, PetMotion.Behavior behavior) {
        if (disposed) return;
        if (behavior == null) throw new IllegalArgumentException("behavior is required");
        int normalizedDirection = ((direction % 8) + 8) % 8;
        if (this.direction != normalizedDirection || this.behavior != behavior) {
            this.direction = normalizedDirection;
            this.behavior = behavior;
            activeElapsedMs = 0;
            invalidate();
        }
    }

    public void resumeAnimation() {
        if (running || disposed) return;
        running = true;
        lastFrameMs = SystemClock.uptimeMillis();
        invalidate();
        handler.postDelayed(frame, pack == null ? WALK_FRAME_MS : 50L);
    }

    public void pauseAnimation() {
        running = false;
        reactionUntilMs = 0;
        handler.removeCallbacks(frame);
    }

    public void reactToTap() {
        if (disposed || !running) return;
        reactionUntilMs = SystemClock.uptimeMillis() + 750L;
        invalidate();
    }

    void cancelReaction() {
        reactionUntilMs = 0;
        if (!disposed) invalidate();
    }

    public void dispose() {
        if (disposed) return;
        pauseAnimation();
        disposed = true;
        // Bitmaps are small and owned by this view; let GC release them safely after any draw finishes.
        for (Bitmap[] frames : walking) {
            frames[0] = null;
            frames[1] = null;
        }
        for (int i = 0; i < 2; i++) {
            idle[i] = null;
            grooming[i] = null;
            napping[i] = null;
        }
        awake = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disposed) return;
        Bitmap bitmap;
        int walkFrame = (int) ((activeElapsedMs / WALK_FRAME_MS) % 2);
        int groomFrame = (int) ((activeElapsedMs / GROOM_FRAME_MS) % 2);
        if (pack != null && skin != null) {
            String state;
            if (SystemClock.uptimeMillis() < reactionUntilMs) state = "awake";
            else if (behavior == PetMotion.Behavior.WALK) state = MOVE_STATES[direction];
            else if (behavior == PetMotion.Behavior.NAP) state = "wait";
            else state = "stop";
            bitmap = pack.frame(skin, state, activeElapsedMs);
            if (bitmap == null) bitmap = idle[0];
        } else if (SystemClock.uptimeMillis() < reactionUntilMs) {
            bitmap = awake;
        } else {
            switch (behavior) {
                case GROOM: bitmap = grooming[groomFrame]; break;
                case NAP: bitmap = napping[(int) ((activeElapsedMs / 1000L) % 2)]; break;
                case IDLE: bitmap = idle[(int) ((activeElapsedMs / 1000L) % 2)]; break;
                default: bitmap = walking[direction][walkFrame]; break;
            }
        }
        float scale = Math.min((float) getWidth()/bitmap.getWidth(), (float) getHeight()/bitmap.getHeight());
        int width = Math.max(1, Math.round(bitmap.getWidth()*scale));
        int height = Math.max(1, Math.round(bitmap.getHeight()*scale));
        int left = (getWidth()-width)/2;
        int top = (getHeight()-height)/2;
        destination.set(left, top, left+width, top+height);
        canvas.drawBitmap(bitmap, null, destination, paint);
    }

    @Override protected void onDetachedFromWindow() {
        pauseAnimation();
        super.onDetachedFromWindow();
    }

    private Bitmap load(int resourceId) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return BitmapFactory.decodeResource(getResources(), resourceId, options);
    }
}
