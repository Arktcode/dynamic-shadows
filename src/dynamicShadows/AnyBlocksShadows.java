package dynamicShadows;

import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import mindustry.core.World;
import mindustry.world.Block;
import mindustry.world.blocks.power.*;

public class AnyBlocksShadows {
    private static final ObjectMap<Block, Float> modCache = new ObjectMap<>();
    private static final ObjectMap<mindustry.type.UnitType, TextureRegion> unitShadowCache = new ObjectMap<>();

    public static float getModifier(Block blk) {
        if (modCache.containsKey(blk))
            return modCache.get(blk, 1f);

        float mod;
        if (blk instanceof PowerNode || blk instanceof LightBlock) {
            mod = 0.0f;
        } else if (blk.isStatic() && blk.solid) {
            mod = 0.20f;
        } else {
            switch (blk.size) {
                case 1:
                    mod = 0.06f;
                    break;
                case 2:
                    mod = 0.08f;
                    break;
                case 3:
                    mod = 0.10f;
                    break;
                case 4:
                    mod = 0.15f;
                    break;
                case 5:
                    mod = 0.22f;
                    break;
                case 6:
                    mod = 0.30f;
                    break;
                default:
                    mod = 0.35f;
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

        float js = size * 0.14f;

        float txBL = (xL + sdx) + jitter(xL, yB) * js;
        float tyBL = (yB + sdy) + jitter(yB, xL + 1f) * js;
        float txBR = (xR + sdx) + jitter(xR, yB + 1f) * js;
        float tyBR = (yB + sdy) + jitter(yB, xR) * js;
        float txTL = (xL + sdx) + jitter(xL, yT + 1f) * js;
        float tyTL = (yT + sdy) + jitter(yT, xL) * js;
        float txTR = (xR + sdx) + jitter(xR + 1f, yT) * js;
        float tyTR = (yT + sdy) + jitter(yT + 1f, xR) * js;

        Fill.quad(xL, yB, xR, yB, xR, yT, xL, yT);
        Fill.quad(txBL, tyBL, txBR, tyBR, txTR, tyTR, txTL, tyTL);
        Fill.quad(xL, yB, xR, yB, txBR, tyBR, txBL, tyBL);
        Fill.quad(xL, yT, xR, yT, txTR, tyTR, txTL, tyTL);
        Fill.quad(xL, yB, xL, yT, txTL, tyTL, txBL, tyBL);
        Fill.quad(xR, yB, xR, yT, txTR, tyTR, txBR, tyBR);
        Fill.quad(xL, yB, xR, yT, txTR, tyTR, txBL, tyBL);
        Fill.quad(xR, yB, xL, yT, txTL, tyTL, txBR, tyBR);
    }

    private static float jitter(float x, float y) {
        int ix = Mathf.round(x * 0.01f);
        int iy = Mathf.round(y * 0.01f);
        int h = ix * 374761393 + iy * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return ((h & 0xFFFF) / 32767.5f) - 1.0f;
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
                return true;
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
