// SPDX-License-Identifier: LGPL-2.1-or-later
package org.foss.wearneko;

import java.util.Random;

/** Small, framework-free wandering state machine. Coordinates are physical screen pixels. */
public final class PetMotion {
    public enum Behavior { WALK, IDLE, GROOM, NAP }

    private static final float MAX_DELTA_SECONDS = 0.25f;
    private static final float EDGE_INSET_PX = 4f;
    private final Random random;
    private final float speedPixelsPerSecond;
    private float centerX;
    private float centerY;
    private float radius;
    private float spriteSize;
    private float x;
    private float y;
    private float targetX;
    private float targetY;
    private float restSeconds;
    private int direction;
    private Behavior behavior = Behavior.WALK;

    public PetMotion(float width, float height, float spriteSize,
            float speedPixelsPerSecond, long seed) {
        if (!isPositiveFinite(speedPixelsPerSecond)) {
            throw new IllegalArgumentException("Travel speed must be positive and finite");
        }
        this.speedPixelsPerSecond = speedPixelsPerSecond;
        random = new Random(seed);
        setBounds(width, height, spriteSize);
    }

    /** Reset safely to the center when screen/window dimensions change. */
    public void setBounds(float width, float height, float spriteSize) {
        if (!isPositiveFinite(width) || !isPositiveFinite(height)
                || !isPositiveFinite(spriteSize)) {
            throw new IllegalArgumentException("Screen and sprite dimensions must be positive and finite");
        }
        this.spriteSize = spriteSize;
        centerX = width / 2f;
        centerY = height / 2f;
        // A half-diagonal inset keeps every sprite corner inside a circular display.
        radius = Math.max(0f, Math.min(width, height) / 2f
                - spriteSize / (float) Math.sqrt(2d) - EDGE_INSET_PX);
        x = centerX;
        y = centerY;
        startWalk();
    }

    /** No catch-up after sleep or a stalled UI thread: excess time is deliberately dropped. */
    public void advance(float deltaSeconds) {
        if (!isPositiveFinite(deltaSeconds)) {
            return;
        }
        float delta = Math.min(deltaSeconds, MAX_DELTA_SECONDS);
        if (behavior != Behavior.WALK) {
            restSeconds -= delta;
            if (restSeconds <= 0f) {
                startWalk();
            }
            return;
        }

        float dx = targetX - x;
        float dy = targetY - y;
        float distance = (float) Math.hypot(dx, dy);
        float step = speedPixelsPerSecond * delta;
        if (distance <= step || distance < 0.01f) {
            x = targetX;
            y = targetY;
            startRest();
        } else {
            // Both endpoints are in the safe disk; their line segment is in it too.
            x += dx / distance * step;
            y += dy / distance * step;
            updateDirection(dx, dy);
        }
    }

    /** Petting pauses the walk briefly; movement resumes without user interaction. */
    public void reactToTap() {
        behavior = Behavior.GROOM;
        restSeconds = 1.5f;
    }

    /** Place a synced companion safely inside the watch's circular usable area. */
    public void placeAt(float centerPositionX, float centerPositionY) {
        if (!Float.isFinite(centerPositionX) || !Float.isFinite(centerPositionY)) {
            throw new IllegalArgumentException("Position must be finite");
        }
        float dx = centerPositionX - centerX;
        float dy = centerPositionY - centerY;
        float distance = (float) Math.hypot(dx, dy);
        float scale = distance > radius && distance > 0f ? radius / distance : 1f;
        x = centerX + dx * scale;
        y = centerY + dy * scale;
        startWalk();
    }

    public float getX() { return x - spriteSize / 2f; }
    public float getY() { return y - spriteSize / 2f; }
    /** 0 east, then clockwise through southeast, south, southwest, west, northwest, north, northeast. */
    public int getDirection() { return direction; }
    public Behavior getBehavior() { return behavior; }

    private void startWalk() {
        behavior = Behavior.WALK;
        // Reject very short strolls when space allows, so the pet visibly roams.
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2d;
            float targetRadius = (float) Math.sqrt(random.nextDouble()) * radius;
            targetX = centerX + (float) Math.cos(angle) * targetRadius;
            targetY = centerY + (float) Math.sin(angle) * targetRadius;
            if (Math.hypot(targetX - x, targetY - y) >= radius * 0.35f) {
                break;
            }
        }
        updateDirection(targetX - x, targetY - y);
    }

    private void startRest() {
        int choice = random.nextInt(10);
        if (choice < 4) {
            behavior = Behavior.IDLE;
            restSeconds = 0.6f + random.nextFloat() * 1.1f;
        } else if (choice < 8) {
            behavior = Behavior.GROOM;
            restSeconds = 1f + random.nextFloat() * 1.5f;
        } else {
            behavior = Behavior.NAP;
            restSeconds = 2f + random.nextFloat() * 1.5f;
        }
    }

    private void updateDirection(float dx, float dy) {
        if (dx != 0f || dy != 0f) {
            int octant = (int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4d));
            direction = (octant + 8) % 8;
        }
    }

    private static boolean isPositiveFinite(float value) {
        return value > 0f && !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
