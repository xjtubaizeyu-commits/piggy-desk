package app.piggy.desk;

/** The terminal's palette (follows Buddy's light/dark mode and accent) and character widths. */
final class TermColors {
    static int DEFAULT_FG = 0xFFE6E1D8, DEFAULT_BG = 0xFF151311, CURSOR = 0xFFD97757, SELECTION = 0x55D97757;
    /** 16 base colours: a soft modern palette, readable on the background. */
    static final int[] PALETTE = new int[256];

    private static final int[] DARK16 = {
            0xFF2A2724, 0xFFF0767B, 0xFF8BD49C, 0xFFF2C36B, 0xFF6FA8F5, 0xFFC792EA, 0xFF5FD4D0, 0xFFD8D3CA,
            0xFF5C5650, 0xFFFF8E92, 0xFFA5E3B3, 0xFFFFD58A, 0xFF8EBCFF, 0xFFD9AAF5, 0xFF7FE6E2, 0xFFF7F3EC,
    };
    private static final int[] LIGHT16 = {
            0xFF2B2622, 0xFFC93C42, 0xFF2E8A4A, 0xFFA66A00, 0xFF2F63C7, 0xFF8A47B8, 0xFF0F8686, 0xFFD9D2C8,
            0xFF6F665E, 0xFFE0505A, 0xFF3BA35C, 0xFFC08410, 0xFF4A7BE0, 0xFFA05CCF, 0xFF1AA0A0, 0xFFF7F3EE,
    };

    static {
        apply(true, 0xFFD97757);
    }

    private TermColors() {
    }

    /** Set from Buddy's theme before drawing. */
    static void apply(boolean dark, int accent) {
        int[] base = dark ? DARK16 : LIGHT16;
        System.arraycopy(base, 0, PALETTE, 0, 16);
        int[] lv = {0, 95, 135, 175, 215, 255};
        for (int i = 0; i < 216; i++) {
            PALETTE[16 + i] = 0xFF000000 | (lv[i / 36] << 16) | (lv[(i / 6) % 6] << 8) | lv[i % 6];
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            PALETTE[232 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        DEFAULT_FG = dark ? 0xFFE6E1D8 : 0xFF2B2622;
        DEFAULT_BG = dark ? 0xFF151311 : 0xFFFBF8F4;
        CURSOR = accent | 0xFF000000;
        SELECTION = (accent & 0x00FFFFFF) | 0x55000000;
    }

    /** ARGB for a cell colour (see TermEmulator), or the default. */
    static int resolve(int c, int def) {
        if (c == 0) return def;
        if ((c & TermEmulator.TRUE) != 0) return 0xFF000000 | (c & 0xFFFFFF);
        return PALETTE[(c - 1) & 0xFF];
    }

    /** "rgb:rrrr/gggg/bbbb" for OSC 10/11 replies. */
    static String xrgb(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return String.format("rgb:%02x%02x/%02x%02x/%02x%02x", r, r, g, g, b, b);
    }

    /** Columns a code point takes: 0 (combining, joiners), 1, or 2 (East Asian wide, emoji). */
    static int width(int c) {
        if (c < 0x300) return 1;
        if ((c >= 0x300 && c <= 0x36F) || (c >= 0x483 && c <= 0x489) || (c >= 0x591 && c <= 0x5BD)
                || (c >= 0x610 && c <= 0x61A) || (c >= 0x64B && c <= 0x65F) || (c >= 0x1AB0 && c <= 0x1AFF)
                || (c >= 0x1DC0 && c <= 0x1DFF) || (c >= 0x200B && c <= 0x200F) || (c >= 0x20D0 && c <= 0x20FF)
                || (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0xFE20 && c <= 0xFE2F) || c == 0xFEFF
                || (c >= 0x1F3FB && c <= 0x1F3FF) || (c >= 0xE0000 && c <= 0xE01EF)) {
            return 0;
        }
        if ((c >= 0x1100 && c <= 0x115F) || (c >= 0x231A && c <= 0x231B) || (c >= 0x23E9 && c <= 0x23EC)
                || c == 0x23F0 || c == 0x23F3 || (c >= 0x25FD && c <= 0x25FE) || (c >= 0x2614 && c <= 0x2615)
                || (c >= 0x2648 && c <= 0x2653) || c == 0x267F || c == 0x2693 || c == 0x26A1 || (c >= 0x26AA && c <= 0x26AB)
                || (c >= 0x26BD && c <= 0x26BE) || (c >= 0x26C4 && c <= 0x26C5) || c == 0x26CE || c == 0x26D4
                || c == 0x26EA || (c >= 0x26F2 && c <= 0x26F3) || c == 0x26F5 || c == 0x26FA || c == 0x26FD
                || c == 0x2705 || (c >= 0x270A && c <= 0x270B) || c == 0x2728 || c == 0x274C || c == 0x274E
                || (c >= 0x2753 && c <= 0x2755) || c == 0x2757 || (c >= 0x2795 && c <= 0x2797) || c == 0x27B0
                || c == 0x27BF || (c >= 0x2B1B && c <= 0x2B1C) || c == 0x2B50 || c == 0x2B55
                || (c >= 0x2E80 && c <= 0x303E) || (c >= 0x3041 && c <= 0x33FF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0xA000 && c <= 0xA4CF) || (c >= 0xA960 && c <= 0xA97F)
                || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE10 && c <= 0xFE19)
                || (c >= 0xFE30 && c <= 0xFE6F) || (c >= 0xFF00 && c <= 0xFF60) || (c >= 0xFFE0 && c <= 0xFFE6)
                || (c >= 0x16FE0 && c <= 0x18AFF) || (c >= 0x1B000 && c <= 0x1B2FF) || c == 0x1F004 || c == 0x1F0CF
                || c == 0x1F18E || (c >= 0x1F191 && c <= 0x1F19A) || (c >= 0x1F200 && c <= 0x1F251)
                || (c >= 0x1F300 && c <= 0x1F320) || (c >= 0x1F32D && c <= 0x1F335) || (c >= 0x1F337 && c <= 0x1F37C)
                || (c >= 0x1F37E && c <= 0x1F393) || (c >= 0x1F3A0 && c <= 0x1F3CA) || (c >= 0x1F3CF && c <= 0x1F3D3)
                || (c >= 0x1F3E0 && c <= 0x1F3F0) || c == 0x1F3F4 || (c >= 0x1F3F8 && c <= 0x1F43E) || c == 0x1F440
                || (c >= 0x1F442 && c <= 0x1F4FC) || (c >= 0x1F4FF && c <= 0x1F53D) || (c >= 0x1F54B && c <= 0x1F54E)
                || (c >= 0x1F550 && c <= 0x1F567) || c == 0x1F57A || (c >= 0x1F595 && c <= 0x1F596) || c == 0x1F5A4
                || (c >= 0x1F5FB && c <= 0x1F64F) || (c >= 0x1F680 && c <= 0x1F6C5) || c == 0x1F6CC
                || (c >= 0x1F6D0 && c <= 0x1F6D2) || (c >= 0x1F6D5 && c <= 0x1F6D7) || (c >= 0x1F6DC && c <= 0x1F6DF)
                || (c >= 0x1F6EB && c <= 0x1F6EC) || (c >= 0x1F6F4 && c <= 0x1F6FC) || (c >= 0x1F7E0 && c <= 0x1F7EB)
                || c == 0x1F7F0 || (c >= 0x1F90C && c <= 0x1F93A) || (c >= 0x1F93C && c <= 0x1F945)
                || (c >= 0x1F947 && c <= 0x1F9FF) || (c >= 0x1FA70 && c <= 0x1FAFF)
                || (c >= 0x20000 && c <= 0x3FFFD)) {
            return 2;
        }
        return 1;
    }
}
