package com.potassium.hud;

/** One draggable row of the HUD, positioned in scaled screen pixels. */
public abstract class HudElement {

    protected int x;
    protected int y;

    /** Stable key used to persist this element's position. */
    public abstract String getId();

    /** Label shown while editing the HUD. */
    public abstract String getDisplayName();

    public abstract int getWidth();

    public abstract int getHeight();

    public abstract boolean isVisible();

    /**
     * False when the element's own toggle hides it. HUD editing skips these too, so an
     * element that is switched off does not sit on the canvas as a ghost.
     */
    public boolean isEnabled() {
        return true;
    }

    /** Draws at the given position instead of the stored one, so the editor can preview. */
    public abstract void render(int x, int y);

    public int getX() {
        return x;
    }

    /** False for elements whose default position follows the screen edge. */
    public boolean hasStoredPosition() {
        return true;
    }

    public int getStoredX() {
        return x;
    }

    public int getStoredY() {
        return y;
    }

    public int getY() {
        return y;
    }

    public void setX(int x) {
        this.x = x;
    }

    public void setY(int y) {
        this.y = y;
    }

    /** Width used for hit testing, so an empty element can still be picked up. */
    public int getHitWidth() {
        return Math.max(12, getWidth());
    }

    public int getHitHeight() {
        return Math.max(9, getHeight());
    }
}
