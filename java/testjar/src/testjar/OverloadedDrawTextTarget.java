package testjar;

public class OverloadedDrawTextTarget {
    public static String lastText = null;
    public static double lastX = 0;

    // Signature 1: String at index 1
    public static void DrawText(Object font, String text, double x, double y) {
        lastText = text;
    }

    // Signature 2: double at index 1 (This is what causes the crash if matched)
    public static void DrawText(String text, double x, double y, double r) {
        lastX = x;
    }
}
