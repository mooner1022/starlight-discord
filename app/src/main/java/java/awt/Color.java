package java.awt;

import java.io.Serializable;

/**
 * Minimal {@code java.awt.Color} for Android, which has no AWT.
 * <p>
 * JDA uses this class in its public API (Role, Member, MessageEmbed, EmbedBuilder, ...). Without it, calling those
 * methods, or a script engine reflecting over these classes, fails with {@code NoClassDefFoundError}. Only the sRGB
 * part of the API is provided: there is no {@code ColorSpace}, {@code Paint} or {@code Transparency} on Android.
 */
public class Color implements Serializable {

    private static final long serialVersionUID = 118526816881161077L;
    private static final double FACTOR = 0.7;

    public static final Color white = new Color(255, 255, 255);
    public static final Color WHITE = white;
    public static final Color lightGray = new Color(192, 192, 192);
    public static final Color LIGHT_GRAY = lightGray;
    public static final Color gray = new Color(128, 128, 128);
    public static final Color GRAY = gray;
    public static final Color darkGray = new Color(64, 64, 64);
    public static final Color DARK_GRAY = darkGray;
    public static final Color black = new Color(0, 0, 0);
    public static final Color BLACK = black;
    public static final Color red = new Color(255, 0, 0);
    public static final Color RED = red;
    public static final Color pink = new Color(255, 175, 175);
    public static final Color PINK = pink;
    public static final Color orange = new Color(255, 200, 0);
    public static final Color ORANGE = orange;
    public static final Color yellow = new Color(255, 255, 0);
    public static final Color YELLOW = yellow;
    public static final Color green = new Color(0, 255, 0);
    public static final Color GREEN = green;
    public static final Color magenta = new Color(255, 0, 255);
    public static final Color MAGENTA = magenta;
    public static final Color cyan = new Color(0, 255, 255);
    public static final Color CYAN = cyan;
    public static final Color blue = new Color(0, 0, 255);
    public static final Color BLUE = blue;

    /** ARGB, like {@link #getRGB()}. */
    private final int value;

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public Color(int r, int g, int b, int a) {
        checkRange("Red", r);
        checkRange("Green", g);
        checkRange("Blue", b);
        checkRange("Alpha", a);
        value = (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Opaque color from {@code 0xRRGGBB}; the upper byte is ignored. */
    public Color(int rgb) {
        value = 0xFF000000 | rgb;
    }

    public Color(int rgba, boolean hasAlpha) {
        value = hasAlpha ? rgba : 0xFF000000 | rgba;
    }

    public Color(float r, float g, float b) {
        this(toInt("Red", r), toInt("Green", g), toInt("Blue", b));
    }

    public Color(float r, float g, float b, float a) {
        this(toInt("Red", r), toInt("Green", g), toInt("Blue", b), toInt("Alpha", a));
    }

    private static void checkRange(String component, int value) {
        if (value < 0 || value > 255)
            throw new IllegalArgumentException(component + " component out of range (0-255): " + value);
    }

    private static int toInt(String component, float value) {
        if (value < 0f || value > 1f)
            throw new IllegalArgumentException(component + " component out of range (0.0-1.0): " + value);
        return (int) (value * 255 + 0.5f);
    }

    public int getRed() {
        return (value >> 16) & 0xFF;
    }

    public int getGreen() {
        return (value >> 8) & 0xFF;
    }

    public int getBlue() {
        return value & 0xFF;
    }

    public int getAlpha() {
        return (value >>> 24) & 0xFF;
    }

    /** ARGB value in the default sRGB color model, e.g. {@code 0xFFFF0000} for opaque red. */
    public int getRGB() {
        return value;
    }

    public Color brighter() {
        int r = getRed(), g = getGreen(), b = getBlue(), a = getAlpha();
        int i = (int) (1.0 / (1.0 - FACTOR));
        if (r == 0 && g == 0 && b == 0)
            return new Color(i, i, i, a);
        if (r > 0 && r < i) r = i;
        if (g > 0 && g < i) g = i;
        if (b > 0 && b < i) b = i;
        return new Color(
            Math.min((int) (r / FACTOR), 255),
            Math.min((int) (g / FACTOR), 255),
            Math.min((int) (b / FACTOR), 255),
            a);
    }

    public Color darker() {
        return new Color(
            Math.max((int) (getRed() * FACTOR), 0),
            Math.max((int) (getGreen() * FACTOR), 0),
            Math.max((int) (getBlue() * FACTOR), 0),
            getAlpha());
    }

    public float[] getRGBComponents(float[] components) {
        float[] result = components != null ? components : new float[4];
        result[0] = getRed() / 255f;
        result[1] = getGreen() / 255f;
        result[2] = getBlue() / 255f;
        result[3] = getAlpha() / 255f;
        return result;
    }

    public float[] getRGBColorComponents(float[] components) {
        float[] result = components != null ? components : new float[3];
        result[0] = getRed() / 255f;
        result[1] = getGreen() / 255f;
        result[2] = getBlue() / 255f;
        return result;
    }

    /** Parses an integer string such as {@code "#FF0000"}, {@code "0xFF0000"} or {@code "16711680"}. */
    public static Color decode(String nm) throws NumberFormatException {
        return new Color(Integer.decode(nm) & 0xFFFFFF);
    }

    public static Color getHSBColor(float h, float s, float b) {
        return new Color(HSBtoRGB(h, s, b));
    }

    public static int HSBtoRGB(float hue, float saturation, float brightness) {
        int r = 0, g = 0, b = 0;
        if (saturation == 0) {
            r = g = b = (int) (brightness * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1.0f - saturation);
            float q = brightness * (1.0f - saturation * f);
            float t = brightness * (1.0f - (saturation * (1.0f - f)));
            switch ((int) h) {
                case 0: r = to255(brightness); g = to255(t); b = to255(p); break;
                case 1: r = to255(q); g = to255(brightness); b = to255(p); break;
                case 2: r = to255(p); g = to255(brightness); b = to255(t); break;
                case 3: r = to255(p); g = to255(q); b = to255(brightness); break;
                case 4: r = to255(t); g = to255(p); b = to255(brightness); break;
                case 5: r = to255(brightness); g = to255(p); b = to255(q); break;
            }
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int to255(float value) {
        return (int) (value * 255.0f + 0.5f);
    }

    public static float[] RGBtoHSB(int r, int g, int b, float[] hsbvals) {
        float[] result = hsbvals != null ? hsbvals : new float[3];
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        float brightness = max / 255.0f;
        float saturation = max != 0 ? (float) (max - min) / max : 0;
        float hue;
        if (saturation == 0) {
            hue = 0;
        } else {
            float range = max - min;
            float redc = (max - r) / range;
            float greenc = (max - g) / range;
            float bluec = (max - b) / range;
            if (r == max)
                hue = bluec - greenc;
            else if (g == max)
                hue = 2.0f + redc - bluec;
            else
                hue = 4.0f + greenc - redc;
            hue = hue / 6.0f;
            if (hue < 0)
                hue = hue + 1.0f;
        }
        result[0] = hue;
        result[1] = saturation;
        result[2] = brightness;
        return result;
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Color && ((Color) obj).value == value;
    }

    @Override
    public String toString() {
        return getClass().getName() + "[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + "]";
    }
}
