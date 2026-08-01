package dynamicShadows;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import mindustry.world.Block;
import mindustry.world.blocks.environment.TreeBlock;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.power.PowerNode;

public class AnyBlocksShadows {
    private static final ObjectMap<Block, Float> modCache = new ObjectMap<>();
    private static final ObjectMap<mindustry.type.UnitType, TextureRegion> unitShadowCache = new ObjectMap<>();

    public static float getModifier(Block blk) {
        synchronized (modCache) {
            if (modCache.containsKey(blk))
                return modCache.get(blk, 1f);
        }

        float mod;
        if (blk instanceof PowerNode || blk instanceof LightBlock || ShadowLayerConfig.isMine(blk)) {
            mod = 0.0f;
        } else if (ShadowLayerConfig.isLogicOrMemory(blk)) {
            mod = 0.04f;
        } else if (blk.isStatic() && blk.solid) {
            mod = 0.20f;
        } else {
            switch (blk.size) {
                case 1:
                    mod = 0.04f;
                    break;
                case 2:
                    mod = 0.08f;
                    break;
                case 3:
                    mod = 0.06f;
                    break;
                case 4:
                    mod = 0.10f;
                    break;
                case 5:
                    mod = 0.135f;
                    break;
                case 6:
                    mod = 0.16f;
                    break;
                case 7:
                    mod = 0.18f;
                    break;
                case 8:
                    mod = 0.20f;
                    break;
                case 9:
                    mod = 0.22f;
                    break;
                default:
                    mod = 0.24f;
                    break;
            }
        }

        synchronized (modCache) {
            modCache.put(blk, mod);
        }
        return mod;
    }

    /**
     * Proyección limpia de polígonos de sombra para bloques rectangulares (1x1, 2x2, 3x3, 4x4).
     * Sin jittering en esquinas para garantizar bordes rectos y limpios.
     */
    public static void draw(float cx, float cy, float size, float len, float cosA, float sinA) {
        float hs = size * 0.5f;
        float sdx = cosA * len;
        float sdy = sinA * len;

        // Shift base fill start position slightly in shadow direction (+cosA * 1.5f, +sinA * 1.5f)
        // so Gaussian blur expansion on the sunlit side lands EXACTLY on the building perimeter,
        // eliminating the dark border on the sunward face completely.
        float shiftX = cosA * 1.5f;
        float shiftY = sinA * 1.5f;

        float xL = cx - hs + shiftX, xR = cx + hs + shiftX;
        float yB = cy - hs + shiftY, yT = cy + hs + shiftY;

        float txBL = xL + sdx;
        float tyBL = yB + sdy;
        float txBR = xR + sdx;
        float tyBR = yB + sdy;
        float txTL = xL + sdx;
        float tyTL = yT + sdy;
        float txTR = xR + sdx;
        float tyTR = yT + sdy;

        Fill.quad(xL, yB, xR, yB, xR, yT, xL, yT);
        Fill.quad(txBL, tyBL, txBR, tyBR, txTR, tyTR, txTL, tyTL);
        Fill.quad(xL, yB, xR, yB, txBR, tyBR, txBL, tyBL);
        Fill.quad(xL, yT, xR, yT, txTR, tyTR, txTL, tyTL);
        Fill.quad(xL, yB, xL, yT, txTL, tyTL, txBL, tyBL);
        Fill.quad(xR, yB, xR, yT, txTR, tyTR, txBR, tyBR);
        Fill.quad(xL, yB, xR, yT, txTR, tyTR, txBL, tyBL);
        Fill.quad(xR, yB, xL, yT, txTL, tyTL, txBR, tyBR);
    }

    public static arc.graphics.g2d.TextureRegion getOriginalShadow(mindustry.type.UnitType type) {
        synchronized (unitShadowCache) {
            if (!unitShadowCache.containsKey(type)) {
                unitShadowCache.put(type, type.shadowRegion);
            }
            return unitShadowCache.get(type);
        }
    }

    public enum PropShadowType { TREE, ORB, SPIKE, GENERIC }

    private static final ObjectMap<Block, PropShadowType> propTypeCache = new ObjectMap<>(64);

    public static PropShadowType getPropType(Block block, TextureRegion region) {
        synchronized (propTypeCache) {
            if (propTypeCache.containsKey(block)) return propTypeCache.get(block);
        }

        PropShadowType type;
        String name = block.name.toLowerCase();

        if (block instanceof TreeBlock) {
            type = PropShadowType.TREE;
        } else if (name.contains("orb") || name.contains("sphere") || name.contains("ball")) {
            type = PropShadowType.ORB;
        } else if (name.contains("spike") || name.contains("thorn") || name.contains("needle")) {
            type = PropShadowType.SPIKE;
        } else if (region != null && region.found()) {
            float ar = region.height / (float) Math.max(region.width, 1);
            type = (ar > 1.4f) ? PropShadowType.TREE : PropShadowType.GENERIC;
        } else {
            type = PropShadowType.GENERIC;
        }

        synchronized (propTypeCache) {
            propTypeCache.put(block, type);
        }
        return type;
    }

    public static void drawPropShadow(float cx, float cy, TextureRegion region,
            PropShadowType type, float propLen, float cosA, float sinA, float angle,
            float contactAlpha) {

        float propW = region.width  * Draw.scl;

        if (contactAlpha > 0.005f) {
            Draw.color(0.02f, 0.015f, 0.04f, contactAlpha);
            Fill.circle(cx, cy, propW * 0.40f);
            Draw.color(0.04f, 0.03f, 0.08f, 1f);
        }
        switch (type) {
            case ORB:
                Fill.circle(cx + cosA * propLen * 0.35f,
                            cy + sinA * propLen * 0.35f,
                            propW * 0.30f);
                break;

            case SPIKE: {
                float bx = cx + cosA * propLen * 0.49f;
                float by = cy + sinA * propLen * 0.49f;
                Draw.rect(region, bx, by, propW * 0.40f, propLen, angle - 90f);
                break;
            }

            case TREE: {
                float bx = cx + cosA * propLen * 0.49f;
                float by = cy + sinA * propLen * 0.49f;
                Draw.rect(region, bx, by, propW * 0.85f, propLen, angle - 90f);
                break;
            }

            default: {
                float actualLen = propLen * 0.65f;
                float bx = cx + cosA * actualLen * 0.49f;
                float by = cy + sinA * actualLen * 0.49f;
                Draw.rect(region, bx, by, propW * 0.85f, actualLen, angle - 90f);
                break;
            }
        }
    }
}
