package dynamicShadows;

import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.struct.ObjectMap;
import mindustry.world.Block;
import mindustry.world.blocks.environment.TreeBlock;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.power.PowerNode;
/**
 * Se encarga de localiar los bloques del juego y ordenarlos por tamaño para dibujar su sombra.
 * Analiza los props y excluye aparte los pine o trees para dibujar sus sombras individuales.
 * @author @Arktcode Arksource
 * @version 1.26
 * @since 2026-10-05
 **/
public class AnyBlocksShadows {
    private static final ObjectMap<Block, Float> modCache = new ObjectMap<>();
    private static final ObjectMap<mindustry.type.UnitType, TextureRegion> unitShadowCache = new ObjectMap<>();

    public static boolean isPine(Block blk) {
        if (blk == null) return false;
        if (blk instanceof TreeBlock) return false;
        String n = blk.name != null ? blk.name.toLowerCase() : "";
        return n.contains("pine") && !n.contains("spore");
    }

    public static float getModifier(Block blk) {
        synchronized (modCache) {
            if (modCache.containsKey(blk))
                return modCache.get(blk, 1f);
        }

        //Esta mierda funciona asi que por eso es mi mejor opción

        float mod;
        if (blk instanceof PowerNode || blk instanceof LightBlock || ShadowLayerConfig.isMine(blk)) {
            mod = 0.0f;
        } else if (isPine(blk)) {
            mod = 0.15f; // 25% más pequeña que las montañas (0.20f * 0.75 = 0.15f)
        } else if (ShadowLayerConfig.isLogicOrMemory(blk)) {
            mod = 0.04f;
        } else if (blk.isStatic() && blk.solid) {
            mod = 0.20f;
        } else {
            switch (blk.size) {
                case 1: mod = 0.04f; break;
                case 2: mod = 0.08f; break;
                case 3: mod = 0.06f; break;
                case 4: mod = 0.10f; break;
                case 5: mod = 0.135f; break;
                case 6: mod = 0.16f; break;
                case 7: mod = 0.18f; break;
                case 8: mod = 0.20f; break;
                case 9: mod = 0.22f; break;
                default: mod = 0.24f; break;
            }
        }
        synchronized (modCache) {
            modCache.put(blk, mod);
        }
        return mod;
    }

    public static void draw(float cx, float cy, float size, float len, float cosA, float sinA) {
        draw(cx, cy, len, cosA, sinA, null, size);
    }

    /**
     * Sombra por extrusión de silueta
     * - Base de rectángulo del bloque (se borra del FBO para evitar autosombra) xd
     * - Laterales: quads que unen la base con el cap
     * - Cap: sprite proyectado en la dirección del sol
     */
    public static void draw(float cx, float cy, float len,
                            float cosA, float sinA, TextureRegion region, float rawSize) {
        float rhs = rawSize * 0.5f;
        float sdx = cosA * len;
        float sdy = sinA * len;

        float ax  = cx - rhs, ay  = cy + rhs;
        float bx  = cx + rhs, by  = cy + rhs;
        float cx2 = cx + rhs, cy2 = cy - rhs;
        float dx  = cx - rhs, dy  = cy - rhs;

        float axP = ax + sdx, ayP = ay + sdy;
        float bxP = bx + sdx, byP = by + sdy;
        float cxP = cx2 + sdx, cyP = cy2 + sdy;
        float dxP = dx  + sdx, dyP = dy  + sdy;

        Fill.quad(ax, ay, bx, by, cx2, cy2, dx, dy);

        if (sdy > 0f) Fill.quad(ax, ay, bx, by, bxP, byP, axP, ayP);
        if (sdy < 0f) Fill.quad(cx2, cy2, dx, dy, dxP, dyP, cxP, cyP);
        if (sdx > 0f) Fill.quad(bx, by, cx2, cy2, cxP, cyP, bxP, byP);
        if (sdx < 0f) Fill.quad(dx, dy, ax, ay, axP, ayP, dxP, dyP);

        float capX = cx + sdx;
        float capY = cy + sdy;
        if (region != null && region.found()) {
            Draw.rect(region, capX, capY, rawSize, rawSize);
        } else {
            Fill.quad(axP, ayP, bxP, byP, cxP, cyP, dxP, dyP);
        }
    }

    public static arc.graphics.g2d.TextureRegion getOriginalShadow(mindustry.type.UnitType type) {
        synchronized (unitShadowCache) {
            if (!unitShadowCache.containsKey(type)) {
                arc.graphics.g2d.TextureRegion clearReg = arc.Core.atlas.find("clear");
                if (type.shadowRegion != null && type.shadowRegion.found() && type.shadowRegion != clearReg) {
                    unitShadowCache.put(type, type.shadowRegion);
                } else if (type.fullIcon != null && type.fullIcon.found()) {
                    unitShadowCache.put(type, type.fullIcon);
                } else if (type.region != null && type.region.found()) {
                    unitShadowCache.put(type, type.region);
                }
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

        if (block instanceof TreeBlock || ShadowLayerConfig.isTree(block)) {
            type = PropShadowType.TREE;
        } else if (name.contains("orb") || name.contains("sphere") || name.contains("ball")) {
            type = PropShadowType.ORB;
        } else if (name.contains("spike") || name.contains("thorn") || name.contains("needle") || name.contains("cluster")) {
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
            float contactAlpha, float alphaMult) {

        float propW = region.width  * Draw.scl;

        if (contactAlpha > 0.005f) {
            Draw.color(0.02f, 0.015f, 0.04f, contactAlpha * alphaMult);
            Fill.circle(cx, cy, propW * 0.40f);
        }
        Draw.color(0.04f, 0.03f, 0.08f, alphaMult);
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
