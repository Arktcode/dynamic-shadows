package dynamicShadows;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mathf;
import arc.struct.ObjectFloatMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.graphics.Layer;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * DynamicShadowRenderer — sombras 2D con ciclo día/noche.
 * Versión Standalone.
 * @author Arksource
 * @category DynamicShadows
 */
public class DynamicShadowRenderer {

    //Configuration of the shadows
    public static float BASE_SHADOW_ANGLE = 210f;
    public static float SHADOW_LENGTH = 10f;
    public static float SHADOW_ALPHA = 0.38f;
    public static float weatherMult = 1f;
    public static boolean enabled = true;

    //Internal State
    private static FrameBuffer fbo;
    private static TextureRegion fboRegion;
    private static final ObjectFloatMap<Block> elevCache = new ObjectFloatMap<>(64);
    private static final Seq<float[]> lightPoints = new Seq<>(false, 16);

    public static void queue() {
        if (!enabled || Vars.headless || !Vars.state.isGame())
            return;

        float darkness = 0f;
        if (Vars.state.rules != null && Vars.state.rules.ambientLight != null) {
            darkness = Mathf.clamp(Vars.state.rules.ambientLight.a);
        }

        //11 min and 30 sec (690s).
        final float rotationTicks = 690f * 60f;
        final float angle = BASE_SHADOW_ANGLE + (arc.util.Time.time / rotationTicks) * 360f;
        
        final float cosA = Mathf.cosDeg(angle);
        final float sinA = Mathf.sinDeg(angle);

        final float shadowLen = SHADOW_LENGTH * Vars.tilesize * (1f + darkness * 1.2f);

        final float alpha = SHADOW_ALPHA * Mathf.clamp(weatherMult) * (0.7f + darkness * 0.3f);

        // Desactiva la sombra original vaciando su textura :p.
        Vars.content.units().each(type -> {
            AnyBlocksShadows.getOriginalShadow(type);
            type.shadowRegion = new arc.graphics.g2d.TextureRegion();
        });
        final float camX = Core.camera.position.x;
        final float camY = Core.camera.position.y;
        final float camW = Core.camera.width;
        final float camH = Core.camera.height;

        float maxShadowTiles = SHADOW_LENGTH * (1f + darkness * 1.2f) * 4.0f + 4f;
        float margin = (maxShadowTiles + 8f) * Vars.tilesize;
        int wMax = Vars.world.width() - 1;
        int hMax = Vars.world.height() - 1;
        final int tx1 = Mathf.clamp((int) ((camX - camW * 0.5f - margin) / Vars.tilesize), 0, wMax);
        final int ty1 = Mathf.clamp((int) ((camY - camH * 0.5f - margin) / Vars.tilesize), 0, hMax);
        final int tx2 = Mathf.clamp((int) ((camX + camW * 0.5f + margin) / Vars.tilesize), 0, wMax);
        final int ty2 = Mathf.clamp((int) ((camY + camH * 0.5f + margin) / Vars.tilesize), 0, hMax);

        lightPoints.clear();
        for (int x = tx1; x <= tx2; x++) {
            for (int y = ty1; y <= ty2; y++) {
                Tile t = Vars.world.tile(x, y);
                if (t != null && t.build != null && t.isCenter()) {
                    Block b = t.build.block;
                    if (b instanceof mindustry.world.blocks.power.PowerNode
                            || b instanceof mindustry.world.blocks.power.LightBlock) {
                        lightPoints.add(new float[] { t.build.x, t.build.y, b.lightRadius });
                    }
                }
            }
        }
        final float[][] lights = lightPoints.toArray(float[].class);
        final float fDarkness = darkness;

        Draw.draw(Layer.floor + 0.1f, () -> {
            // Preparar FBO
            int gw = Core.graphics.getWidth();
            int gh = Core.graphics.getHeight();
            if (fbo == null || fbo.getWidth() != gw || fbo.getHeight() != gh) {
                if (fbo != null)
                    fbo.dispose();
                fbo = new FrameBuffer(gw, gh);
                fboRegion = new TextureRegion(fbo.getTexture());
                fboRegion.flip(false, true);
            }

            Draw.flush();

            fbo.begin(Color.clear);
            Draw.color(Color.black, 1f);

            for (int x = tx1; x <= tx2; x++) {
                for (int y = ty1; y <= ty2; y++) {
                    Tile tile = Vars.world.tile(x, y);
                    if (tile == null)
                        continue;

                    boolean isBuild = tile.build != null && tile.isCenter();
                    boolean isWall = !isBuild && tile.block().solid && !isBuriedWall(x, y);
                    if (!isBuild && !isWall)
                        continue;

                    if (isBuild && tile.build.block.emitLight && tile.build.block.lightRadius > 30f)
                        continue;

                    float cx = isBuild ? tile.build.x : tile.worldx();
                    float cy = isBuild ? tile.build.y : tile.worldy();
                    float size = isBuild ? tile.build.block.size * Vars.tilesize : Vars.tilesize;

                    float elev = isBuild ? getElev(tile.build.block, 1f)
                            : getElev(tile.block(), 1.6f);

                    float shadowMod = AnyBlocksShadows.getModifier(isBuild ? tile.build.block : tile.block());

                    float maxLightInfluence = 0f;
                    if (lights.length > 0) {
                        for (float[] lp : lights) {
                            if (lp[2] > 30f) {
                                float dst = arc.math.Mathf.dst(cx, cy, lp[0], lp[1]);
                                if (dst < lp[2]) {
                                    float inf = 1f - (dst / lp[2]);
                                    if (inf > maxLightInfluence)
                                        maxLightInfluence = inf;
                                }
                            }
                        }
                    }

                    float finalLen = SHADOW_LENGTH * Vars.tilesize * (1f + fDarkness * 1.2f) * elev * shadowMod;
                    finalLen *= (1f - maxLightInfluence);

                    if (finalLen < 0.2f)
                        continue;

                    AnyBlocksShadows.draw(cx, cy, size, finalLen, cosA, sinA);
                }
            }

            // 1B. Draw shadows of units
            mindustry.gen.Groups.unit.intersect(camX - margin, camY - margin, camW + margin * 2f, camH + margin * 2f,
                    u -> {
                        float uElev = 1.0f + u.elevation * 2.8f;
                        float fl = shadowLen * uElev * 0.025f;

                        Draw.rect(AnyBlocksShadows.getOriginalShadow(u.type), u.x + cosA * fl, u.y + sinA * fl,
                                u.rotation - 90);
                    });

            Draw.flush();
            Draw.blend(arc.graphics.Blending.disabled);
            Draw.color(Color.clear);

            // 2A. Delete shadow of space and void tiles
            for (int x = tx1; x <= tx2; x++) {
                for (int y = ty1; y <= ty2; y++) {
                    Tile tile = Vars.world.tile(x, y);
                    boolean isSpace = false;

                    if (tile == null) {
                        isSpace = true;
                    } else {
                        Block floor = tile.floor();
                        if (floor != null) {
                            String fn = floor.name;
                            if (floor == mindustry.content.Blocks.space || floor == mindustry.content.Blocks.empty ||
                                    fn.contains("space") || fn.contains("void") || fn.contains("empty")
                                    || fn.contains("dark-panel")) {
                                isSpace = true;
                            }
                        }
                    }

                    if (isSpace && (tile == null || (tile.build == null && !tile.block().solid))) {
                        Fill.rect(x * Vars.tilesize, y * Vars.tilesize, Vars.tilesize, Vars.tilesize);
                    }
                }
            }

            // 2.5. Hole shadows
            for (float[] lp : lights) {
                AnyBlocksShadows.drawOccludedLight(lp[0], lp[1], 32f);
            }

            Draw.flush();
            Draw.blend(arc.graphics.Blending.normal);
            fbo.end();

            // Draw FBO
            Draw.color(Color.white, alpha);
            Draw.rect(fboRegion, camX, camY, camW, camH);

            Draw.color();
        });
    }

    private static boolean isBuriedWall(int x, int y) {
        return isWallAt(x + 1, y) && isWallAt(x - 1, y) && isWallAt(x, y + 1) && isWallAt(x, y - 1);
    }

    private static boolean isWallAt(int x, int y) {
        Tile t = Vars.world.tile(x, y);
        return t != null && t.build == null && t.block().solid;
    }

    private static float getElev(Block b, float def) {
        if (elevCache.containsKey(b))
            return elevCache.get(b, def);
        float v = def;
        try {
            v = b.getClass().getField("shadowElevation").getFloat(b);
        } catch (Exception ignored) {
        }
        elevCache.put(b, v);
        return v;
    }
}
