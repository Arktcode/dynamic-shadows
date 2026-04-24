package dynamicShadows;

import arc.graphics.g2d.Fill;
import arc.struct.ObjectFloatMap;
import mindustry.core.World;
import mindustry.world.Block;
import mindustry.world.blocks.power.*;

/**
 * Sombreado Dinamico para cualquier tipo de bloque
@author Arksource
*/
public class AnyBlocksShadows {
    private static final ObjectFloatMap<Block> modCache = new ObjectFloatMap<>(128);
    private static final arc.struct.ObjectMap<mindustry.type.UnitType, arc.graphics.g2d.TextureRegion> unitShadowCache = new arc.struct.ObjectMap<>();

    /** @return El multiplicador de longitud de sombra para un bloque específico. */
    public static float getModifier(Block blk) {
        if (modCache.containsKey(blk))
            return modCache.get(blk, 1f);

        float mod = 0.8f;
        if (blk instanceof PowerNode || blk instanceof LightBlock) {
            mod = 0.0f;
        }
        else if (blk.isStatic() && blk.solid) {
            mod = (blk.size == 1) ? 0.2f : 0.20f;
        }
        //Based on the size of the block (Artificial Structures)
        else {
            switch (blk.size) {
                case 1:
                    mod = 0.013f;
                    break;
                case 2:
                    mod = 0.02f;
                    break;
                case 3:
                    mod = 0.03f;
                    break;
                case 4:
                    mod = 0.04f;
                    break;
                case 5:
                    mod = 0.05f;
                    break;
                case 6:
                    mod = 0.06f;
                    break;
                default:
                    mod = 0.07f;
                    break;
            }
        }

        modCache.put(blk, mod);
        return mod;
    }

    public static void draw(float cx, float cy, float size, float len, float cosA, float sinA) {
        float hs = size * 0.5f;
        float sdx = cosA * len;
        float sdy = sinA * len;
        float xL = cx - hs, xR = cx + hs;
        float yB = cy - hs, yT = cy + hs;

        // 1. Capas base
        Fill.quad(xL, yB, xR, yB, xR, yT, xL, yT);
        Fill.quad(xL + sdx, yB + sdy, xR + sdx, yB + sdy, xR + sdx, yT + sdy, xL + sdx, yT + sdy);

        // 2. Paredes laterales
        Fill.quad(xL, yB, xR, yB, xR + sdx, yB + sdy, xL + sdx, yB + sdy);
        Fill.quad(xL, yT, xR, yT, xR + sdx, yT + sdy, xL + sdx, yT + sdy);
        Fill.quad(xL, yB, xL, yT, xL + sdx, yT + sdy, xL + sdx, yB + sdy);
        Fill.quad(xR, yB, xR, yT, xR + sdx, yT + sdy, xR + sdx, yB + sdy);

        // 3. Relleno Cúbico Interno
        Fill.quad(xL, yB, xR, yT, xR + sdx, yT + sdy, xL + sdx, yB + sdy);
        Fill.quad(xR, yB, xL, yT, xL + sdx, yT + sdy, xR + sdx, yB + sdy);
    }

    public static void drawOccludedLight(float lx, float ly, float radius) {
        int segments = 40;
        float angleStep = 360f / segments;

        for (int i = 0; i < segments; i++) {
            float a1 = i * angleStep;
            float a2 = (i + 1) * angleStep;

            float d1 = cast(lx, ly, a1, radius);
            float d2 = cast(lx, ly, a2, radius);

            float x1 = lx + arc.math.Mathf.cosDeg(a1) * d1;
            float y1 = ly + arc.math.Mathf.sinDeg(a1) * d1;
            float x2 = lx + arc.math.Mathf.cosDeg(a2) * d2;
            float y2 = ly + arc.math.Mathf.sinDeg(a2) * d2;

            Fill.tri(lx, ly, x1, y1, x2, y2);
        }
    }

    private static float cast(float lx, float ly, float angle, float radius) {
        final float[] result = { radius };
        float dx = arc.math.Mathf.cosDeg(angle);
        float dy = arc.math.Mathf.sinDeg(angle);

        World.raycastEachWorld(lx, ly, lx + dx * radius, ly + dy * radius, (x, y) -> {
            mindustry.world.Tile tile = mindustry.Vars.world.tile(x, y);
            if (tile != null && tile.block().solid) {
                if (Math.abs(tile.worldx() - lx) < 1f && Math.abs(tile.worldy() - ly) < 1f)
                    return false;

                float d = arc.math.Mathf.dst(lx, ly, tile.worldx(), tile.worldy());
                if (d < result[0])
                    result[0] = d;
                return true; // Stop
            }
            return false;
        });

        return result[0];
    }

    public static arc.graphics.g2d.TextureRegion getOriginalShadow(mindustry.type.UnitType type) {
        if (!unitShadowCache.containsKey(type)) {
            unitShadowCache.put(type, type.shadowRegion);
        }
        return unitShadowCache.get(type);
    }
}
