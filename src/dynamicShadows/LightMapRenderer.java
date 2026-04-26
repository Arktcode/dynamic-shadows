package dynamicShadows;

import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.world.*;

public class LightMapRenderer {
    public static boolean enabled = true;
    public static float intensityMult = 1.0f;
    private static final Seq<LightEntry> blockLights = new Seq<>(false, 64);
    private static boolean cacheValid = false;

    public static void invalidateCache() { cacheValid = false; }

    public static void maybeRebuildCache(int tx1, int ty1, int tx2, int ty2) {
        if (!cacheValid) {
            blockLights.clear();
            for (int x = tx1; x <= tx2; x++) {
                for (int y = ty1; y <= ty2; y++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t == null || t.build == null || !t.isCenter()) continue;
                    LightEntry le = LightSourceScanner.classify(t.build.block, t.build);
                    if (le != null) blockLights.add(le);
                }
            }
            cacheValid = true;
        }
    }

    public static void eraseShadowsInLitAreas(float darkness) {
        if (!enabled || blockLights.isEmpty()) return;
        float nightScale = 0.20f + darkness * 0.80f;
        Draw.flush();
        Draw.blend(arc.graphics.Blending.disabled);
        Draw.color(Color.clear);
        for (LightEntry le : blockLights) {
            float clearR = le.radius * 0.55f * le.intensity * nightScale * intensityMult;
            if (clearR < 4f) continue;
            Fill.circle(le.x, le.y, clearR * 0.50f);
            for (int i = 1; i <= 5; i++) Fill.circle(le.x, le.y, clearR * (0.50f + (i/5f) * 0.70f));
        }
        Draw.flush();
        Draw.blend(arc.graphics.Blending.normal);
    }

    public static Seq<LightEntry> getCachedLights() { return blockLights; }
}
