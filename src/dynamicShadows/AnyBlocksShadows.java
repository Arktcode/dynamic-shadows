package dynamicShadows;

import arc.graphics.g2d.*;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import mindustry.Vars;
import mindustry.world.Block;
import mindustry.world.blocks.environment.TreeBlock;
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

    /**
     * Draws a shadow volume using UNIFORM alpha on every quad.
     * KEY FIX: All quads share the same Draw color/alpha so overlapping regions
     * do NOT produce darker triangles. The shadow.frag shader handles soft edge :P
     */
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



    public static arc.graphics.g2d.TextureRegion getOriginalShadow(mindustry.type.UnitType type) {
        if (!unitShadowCache.containsKey(type)) {
            unitShadowCache.put(type, type.shadowRegion);
        }
        return unitShadowCache.get(type);
    }

    // Prop shadow system

    /** Shadow rendering category for environment props. */
    public enum PropShadowType { TREE, ORB, SPIKE, GENERIC }

    // Per-block caches — filled once on first encounter, never GC-pressured
    private static final ObjectMap<Block, PropShadowType> propTypeCache = new ObjectMap<>(64);
    private static final ObjectMap<Block, Float>          propElevCache = new ObjectMap<>(64);

    /**
     * Classifies a prop into a shadow rendering category.
     * Result is cached per block — zero cost on subsequent calls.
     */
    public static PropShadowType getPropType(Block block, TextureRegion region) {
        if (propTypeCache.containsKey(block)) return propTypeCache.get(block);

        PropShadowType type;
        String name = block.name.toLowerCase();

        if (block instanceof TreeBlock) {
            // Mindustry tree class tall plant shadow
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

        propTypeCache.put(block, type);
        return type;
    }

    /**
     * Estimates the "height" of a prop from its sprite dimensions.
     * Taller sprites produce longer shadows at the same sun angle.
     * Result is cached per block — zero cost on subsequent calls.
     */
    public static float getPropElevation(Block block, TextureRegion region) {
        if (propElevCache.containsKey(block)) return propElevCache.get(block, 0.3f);
        float propH = region.height * Draw.scl;
        // Normalize by 2× tile height so a 2-tile-tall sprite = elevation 1.0
        float elev = Mathf.clamp(propH / (Vars.tilesize * 2f), 0.08f, 1.80f);
        propElevCache.put(block, elev);
        return elev;
    }

    /**
     * Draws a realistic prop shadow:
     *  1. A contact disk at the prop base (shows it is grounded).
     *  2. A body shadow displaced in the sun direction, shaped by PropShadowType.
     * Caller must set Draw.color to the desired shadow color before calling.
     * This method may temporarily change Draw.color for the contact disk,
     * and restores it before drawing the body shadow.
     *
     * @param cx            prop world X
     * @param cy            prop world Y
     * @param region        sprite region (used for width/height reference)
     * @param type          shadow shape category
     * @param propLen       world-space shadow body length
     * @param cosA          cos of sun direction angle
     * @param sinA          sin of sun direction angle
     * @param angle         sun direction angle in degrees (for Draw.rect rotation)
     * @param contactAlpha  opacity of contact disk (0 = skip)
     */
    public static void drawPropShadow(float cx, float cy, TextureRegion region,
            PropShadowType type, float propLen, float cosA, float sinA, float angle,
            float contactAlpha) {

        float propW = region.width  * Draw.scl;

        if (contactAlpha > 0.005f) {
            Draw.color(0.02f, 0.015f, 0.04f, contactAlpha);
            Fill.circle(cx, cy, propW * 0.40f);
            // Restore body-shadow color
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
