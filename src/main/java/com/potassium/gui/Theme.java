package com.potassium.gui;

/**
 * Palette sampled from the reference client's menu: near-black slate plates, a header band one
 * step lighter, grey labels and a full-width banana-amber highlight on the modules that are on.
 * The default accent is the banana gold from the Potassium palette.
 */
public class Theme {

    /** Row plate shared by every module list. */
    public static final int PANEL_BG = 0xF0131519;
    /** Category header band, one step lighter than the rows. */
    public static final int HEADER_BG = 0xF01E1F23;
    /** Settings area under an expanded module. */
    public static final int BODY_BG = 0xF00E1014;
    /** Search box outline; the panels themselves are borderless. */
    public static final int PANEL_BORDER = 0x1FFFFFFF;
    /** Thin dark rule between rows, spanning the full panel width. */
    public static final int SEPARATOR = 0x30000000;
    public static final int HOVER = 0x16FFFFFF;
    /** Flat plate behind a dropdown value. */
    public static final int FIELD_BG = 0x3D000000;
    public static final int POPUP_BG = 0xFF14161C;
    public static final int POPUP_BORDER = 0xFF08090C;
    public static final int TOGGLE_OFF = 0xFF343A48;
    public static int ACCENT = 0xFFF0A612;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFD6DAE1;
    /** Disabled row labels. */
    public static final int TEXT_GRAY = 0xFFB2B4B7;
    public static final int TEXT_FAINT = 0xFF6F7276;
    public static final int TRACK = 0xFF2A2F3C;
    public static final int KNOB = 0xFFFFFFFF;
}
