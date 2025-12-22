package testjar;

public class MouseCoordinates {
    private static double lastX = 0.0;
    private static double lastY = 0.0;
    
    // Mimics Mouse.addMoveEvent() - stores mouse coordinates
    public static void addMoveEvent(double mouseX, double mouseY) {
        lastX = mouseX;
        lastY = mouseY;
    }
    
    public static double getLastX() {
        return lastX;
    }
    
    public static double getLastY() {
        return lastY;
    }
}

