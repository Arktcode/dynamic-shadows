package dynamicShadows;

public class LightEntry {
    public float x, y;
    public float radius;
    public float r, g, b;
    public float intensity;
    public boolean isStrongLight = false;

    public LightEntry(float x, float y, float radius,
                      float r, float g, float b, float intensity) {
        this.x = x; this.y = y;
        this.radius = radius;
        this.r = r; this.g = g; this.b = b;
        this.intensity = intensity;
    }
}
