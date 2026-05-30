package dynamicShadows;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mathf;
import arc.struct.*;
import mindustry.Vars;
import mindustry.world.*;
import mindustry.world.blocks.environment.Floor;

public class DynamicShadowRenderer {
    public static float BASE_SHADOW_ANGLE = 210f, SHADOW_LENGTH = 10f, SHADOW_ALPHA = 0.38f, weatherMult = 1f;
    public static boolean enabled = true, dayNightCycle = true;
    public static float darkFadeThreshold = 0.35f, darkFadeStrength = 0.80f, blurRadius = 3.5f, edgeNoise = 0.38f;
    public static float shadowTint = 0.60f, contactShadow = 0.45f;
    public static float propShadowScale = 1.0f;
    public static boolean unitShadowsEnabled = true;
    public static boolean oldShadowsEnabled = false;

    public static void updateUnitShadows() {
        if (Vars.headless) return;
        TextureRegion emptyReg = arc.Core.atlas.find("clear");
        mindustry.Vars.content.units().each(type -> {
            AnyBlocksShadows.getOriginalShadow(type);
            if (unitShadowsEnabled && enabled) {
                if (type.shadowRegion != emptyReg) type.shadowRegion = emptyReg;
            } else {
                TextureRegion orig = AnyBlocksShadows.getOriginalShadow(type);
                if (orig != null && type.shadowRegion != orig) type.shadowRegion = orig;
            }
        });
    }

    /** 0 = Low (50% res), 1 = Medium (75% res), 2 = High (100% res, default) */
    public static int graphicsQuality = 2;

    public static FrameBuffer fbo;
    private static FrameBuffer fbo2, fbo3;
    public static TextureRegion fboReg;
    private static TextureRegion fboReg2, fboReg3;
    private static ShadowShader shadowShader;
    private static final ObjectFloatMap<Block> elevCache = new ObjectFloatMap<>(64);
    private static TextureRegion emptyRegion;

    // Last FBO scale used, to detect when quality changes require FBO rebuild
    private static float lastFboScale = -1f;

    public static float currentSunElevation = 0f, currentCycleProgress = 0f;

    public static void queue() {
        if (!enabled || Vars.headless || !Vars.state.isGame()) return;
        if (emptyRegion == null) emptyRegion = Core.atlas.find("clear");

        final float rotTicks    = 1620f * 60f;
        final float cycleProgress = (arc.util.Time.time / rotTicks) % 1f;
        currentCycleProgress    = cycleProgress;

        final float angle = BASE_SHADOW_ANGLE + cycleProgress * 360f;
        final float cosA  = Mathf.cosDeg(angle);
        final float sinA  = Mathf.sinDeg(angle);

        // Sun elevation
        float rawSun = Mathf.sin(cycleProgress * Mathf.PI2);   // -1..+1
        currentSunElevation = rawSun;

        // Darkness
        float darkness = 0f;
        if (dayNightCycle) {
            if (rawSun < 0.64f) {
                float np = (0.64f - rawSun) / 1.64f;
                darkness = Mathf.clamp(np * 0.99f, 0f, 0.70f);
            }
            if (Vars.state.rules != null) {
                Vars.state.rules.lighting = true;
                if (Vars.state.rules.ambientLight == null)
                    Vars.state.rules.ambientLight = new Color(0,0,0,0);
                Vars.state.rules.ambientLight.set(0,0,0, darkness);
            }
        } else if (Vars.state.rules != null && Vars.state.rules.ambientLight != null) {
            darkness = Mathf.clamp(Vars.state.rules.ambientLight.a);
        }

        // Dark fade (shadows dissolve near dusk/night)
        float darkFade = 1f;
        if (darkness > darkFadeThreshold) {
            float depth = Mathf.clamp((darkness - darkFadeThreshold) / (1f - darkFadeThreshold), 0f, 1f);
            darkFade = 1f - depth * darkFadeStrength;
        }

        final float shadowLen   = SHADOW_LENGTH * Vars.tilesize * (1f + darkness * 1.2f);
        final float shadowScale = shadowLen; // capture outside lambda — avoids redundant mul inside draw call
        final float alpha       = SHADOW_ALPHA * Mathf.clamp(weatherMult) * (1f - darkness * 0.5f) * darkFade;

        // Viewport tile range — margin equals actual shadow length + small buffer
        final float camX = Core.camera.position.x, camY = Core.camera.position.y;
        final float camW = Core.camera.width,       camH = Core.camera.height;
        float margin  = shadowLen + Vars.tilesize * 4f;
        int wMax = Vars.world.width()-1, hMax = Vars.world.height()-1;
        final int tx1 = Mathf.clamp((int)((camX-camW*.5f-margin)/Vars.tilesize),0,wMax);
        final int ty1 = Mathf.clamp((int)((camY-camH*.5f-margin)/Vars.tilesize),0,hMax);
        final int tx2 = Mathf.clamp((int)((camX+camW*.5f+margin)/Vars.tilesize),0,wMax);
        final int ty2 = Mathf.clamp((int)((camY+camH*.5f+margin)/Vars.tilesize),0,hMax);

        final float fDark = darkness, fSunElev = rawSun, fDarkFade = darkFade;
        final float screenX1 = camX - camW * 0.5f - margin;
        final float screenY1 = camY - camH * 0.5f - margin;
        final float screenX2 = camX + camW * 0.5f + margin;
        final float screenY2 = camY + camH * 0.5f + margin;

        // FBO scale from quality setting
        final float fboScale = qualityScale();

        Draw.draw(18f, () -> {
            int gw = Core.graphics.getWidth(), gh = Core.graphics.getHeight();
            // Cap FBO dimensions to prevent crash or OOM on extremely high resolution screens (e.g. 4K+)
            int maxFboDim = 3840;
            int fw = Mathf.clamp((int)(gw * fboScale), 1, maxFboDim);
            int fh = Mathf.clamp((int)(gh * fboScale), 1, maxFboDim);

            if (fbo == null || fbo.getWidth()!=fw || fbo.getHeight()!=fh
                    || fbo.getTexture()==null || fbo.getTexture().getTextureObjectHandle()==0
                    || lastFboScale != fboScale) {
                disposeFBOs();
                lastFboScale = fboScale;
                try {
                    fbo   = new FrameBuffer(fw,fh); fboReg   = flipped(fbo);
                    fbo2  = new FrameBuffer(fw,fh); fboReg2  = flipped(fbo2);
                    fbo3  = new FrameBuffer(fw,fh); fboReg3  = flipped(fbo3);

                    // Linear filter prevents sub-pixel shimmering when scaling FBO to screen
                    fbo.getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                    fbo2.getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                    fbo3.getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                } catch (Exception e) { return; }
            }

            fbo.begin();
            arc.graphics.Gl.clearColor(0f, 0f, 0f, 0f);
            arc.graphics.Gl.clear(arc.graphics.GL20.GL_COLOR_BUFFER_BIT);
            Draw.color(0.04f, 0.03f, 0.08f);
            if (!ChunkCache.initialized) {
                ChunkCache.init();
            }

            int chX1 = tx1 / ChunkCache.CHUNK_SIZE;
            int chY1 = ty1 / ChunkCache.CHUNK_SIZE;
            int chX2 = tx2 / ChunkCache.CHUNK_SIZE;
            int chY2 = ty2 / ChunkCache.CHUNK_SIZE;

            for (int cx = chX1; cx <= chX2; cx++) {
                for (int cy = chY1; cy <= chY2; cy++) {
                    if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                    ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                    if (!chunk.valid) {
                        ChunkCache.rebuildChunk(cx, cy);
                    }

                    for (int i = 0; i < chunk.casters.size; i++) {
                        ChunkCache.CasterEntry e = chunk.casters.get(i);
                        
                        // Early exit if block has modifier = 0
                        if (e.mod == 0f) continue;

                        // Per-caster frustum cull
                        float whs = e.size * 0.5f + 1f;
                        float bx1 = Math.min(e.cx - whs, e.cx - whs + cosA * shadowScale);
                        float by1 = Math.min(e.cy - whs, e.cy - whs + sinA * shadowScale);
                        float bx2 = Math.max(e.cx + whs, e.cx + whs + cosA * shadowScale);
                        float by2 = Math.max(e.cy + whs, e.cy + whs + sinA * shadowScale);
                        if (bx2 < screenX1 || bx1 > screenX2 || by2 < screenY1 || by1 > screenY2) continue;

                        if (e.isProp) {
                            float propH = e.region.height * Draw.scl;
                            float propLen = propH * (shadowScale / 80f) * 1.35f
                                            * propShadowScale;
                            if (propLen < 0.4f) continue;

                            float propContactAlpha = contactShadow * 0.55f * fDarkFade;

                            Draw.color(0.04f, 0.03f, 0.08f, 1f);
                            AnyBlocksShadows.drawPropShadow(
                                    e.cx, e.cy, e.region, e.propType, propLen,
                                    cosA, sinA, angle, propContactAlpha);
                        } else {
                            float fLen = shadowScale * e.elev * e.mod;
                            if (fLen < 0.2f) continue;

                            if (contactShadow > 0f) {
                                float diskR = e.size * 0.52f * (0.6f + e.elev * 0.4f);
                                Draw.color(0.02f, 0.015f, 0.04f, contactShadow * 0.70f);
                                Fill.circle(e.cx, e.cy, diskR);
                                Draw.color(0.04f, 0.03f, 0.08f, 1f);
                            }
                            AnyBlocksShadows.draw(e.cx, e.cy, e.size, fLen, cosA, sinA);
                        }
                    }
                }
            }
            
            // Unit shadows in same pass as blocks — same shader, same tint
            if (unitShadowsEnabled) {
                mindustry.gen.Groups.unit.intersect(screenX1, screenY1, camW + margin * 2f, camH + margin * 2f, u -> {
                    TextureRegion us = AnyBlocksShadows.getOriginalShadow(u.type);
                    if (us != null && us.found()) {
                        float ufl = shadowScale * (1f + u.elevation * 2.8f) * 0.03f;
                        float uw = us.width * Draw.scl;
                        float uh = us.height * Draw.scl;
                        // High-elevation units have larger (more dispersed) and fainter shadows
                        float sizeMult = 1f + u.elevation * 0.22f;
                        float alphaMult = Mathf.clamp(1f - u.elevation * 0.50f, 0.25f, 1f);
                        Draw.color(0.04f, 0.03f, 0.08f, alphaMult);
                        Draw.rect(us, u.x + cosA * ufl, u.y + sinA * ufl, uw * sizeMult, uh * sizeMult, u.rotation - 90);
                    }
                });
            }

            // ── Erase shadows on glowing/space tiles ─────────────────────────────
            // We erase the shadow FBO inside space/liquid tiles exactly at their boundaries.
            // A small margin (ts + 0.4f) prevents sub-pixel gaps between adjacent tiles.
            // The subsequent Gaussian blur pass will soften the edge at the border.
            final float ts = Vars.tilesize;
            Draw.flush();
            Draw.blend(arc.graphics.Blending.disabled);
            Draw.color(0f, 0f, 0f, 0f); // fully transparent to erase
            for (int ex = tx1; ex <= tx2; ex++) {
                for (int ey = ty1; ey <= ty2; ey++) {
                    if (shouldEraseShadow(Vars.world.tile(ex, ey))) {
                        Fill.rect(ex * ts, ey * ts, ts + 0.4f, ts + 0.4f);
                    }
                }
            }
            Draw.flush();
            Draw.blend(arc.graphics.Blending.normal);
            fbo.end();

            fbo2.begin();
            arc.graphics.Gl.clearColor(0f, 0f, 0f, 0f);
            arc.graphics.Gl.clear(arc.graphics.GL20.GL_COLOR_BUFFER_BIT);
            applyShader(1f, 0f, fSunElev, fboReg, camX, camY, camW, camH);
            Draw.flush();
            fbo2.end();

            fbo3.begin();
            arc.graphics.Gl.clearColor(0f, 0f, 0f, 0f);
            arc.graphics.Gl.clear(arc.graphics.GL20.GL_COLOR_BUFFER_BIT);
            applyShader(0f, 1f, fSunElev, fboReg2, camX, camY, camW, camH);
            Draw.flush();
            fbo3.end();

            if (fboReg3 != null && fboReg3.texture != null) {
                Draw.color(Color.white, alpha);
                Draw.rect(fboReg3, camX, camY, camW, camH);
                Draw.color();
            }
        });
    }

    // Helpers

    /** FBO render scale based on graphics quality setting. */
    private static float qualityScale() {
        switch (graphicsQuality) {
            case 0:  return 0.50f; // Low
            case 1:  return 0.75f; // Medium
            default: return 1.00f; // High
        }
    }

    private static void applyShader(float dx, float dy, float sunElev, TextureRegion src, float cx, float cy, float cw, float ch) {
        if (shadowShader==null) shadowShader = new ShadowShader();
        float currentPpu = (float)Core.graphics.getWidth() / Core.camera.width;
        // Reference PPU is 4.0f (32px per tile). Scale down when zooming out to keep shadows visible,
        // but clamp when zooming in so the shadow core remains solid and sharp.
        float ppuScale = Math.min(1.0f, currentPpu / 4.0f);
        shadowShader.radius = Mathf.clamp(blurRadius * ppuScale, 0.5f, blurRadius);
        shadowShader.blurDirX = dx;
        shadowShader.blurDirY = dy;
        shadowShader.edgeNoise = edgeNoise;
        shadowShader.shadowTint = shadowTint;
        shadowShader.contactShadow = contactShadow;
        shadowShader.sunElevation = sunElev;
        shadowShader.camW = Core.camera.width;
        shadowShader.camH = Core.camera.height;
        Draw.blend(arc.graphics.Blending.disabled);
        Draw.flush();
        Draw.shader(shadowShader);
        Draw.color(Color.white, 1f);
        Draw.rect(src, cx,cy,cw,ch);
        Draw.flush();
        Draw.shader();
        Draw.blend(arc.graphics.Blending.normal);
    }

    private static TextureRegion flipped(FrameBuffer fb) {
        TextureRegion r = new TextureRegion(fb.getTexture()); r.flip(false,true); return r;
    }

    private static void disposeFBOs() {
        for (FrameBuffer b : new FrameBuffer[]{fbo, fbo2, fbo3})
            if (b != null) try { b.dispose(); } catch (Exception ignored) {}
        fbo = fbo2 = fbo3 = null;
    }

    private static boolean isBuriedWall(int x,int y) {
        return isWallAt(x+1,y)&&isWallAt(x-1,y)&&isWallAt(x,y+1)&&isWallAt(x,y-1);
    }
    private static boolean isWallAt(int x,int y) {
        Tile t=Vars.world.tile(x,y); return t!=null&&t.build==null&&t.block().solid;
    }
    private static float getElev(Block b,float def) {
        if (elevCache.containsKey(b)) return elevCache.get(b,def);
        float v=def;
        try { v=b.getClass().getField("shadowElevation").getFloat(b); } catch (Exception ignored){}
        elevCache.put(b,v); return v;
    }
    /**
     * Returns true for floor tiles that emit significant light — no shadow should fall on them.
     * Used by ChunkCache to skip adding those tiles as casters.
     */
    private static boolean isLuminousFloor(Floor floor) {
        if (floor == null || floor.name == null) return false;
        String n = floor.name.toLowerCase();
        return n.contains("slag") || n.contains("lava") || n.contains("magma") || n.contains("hot") || n.contains("cryo");
    }

    /**
     * Returns true for tiles where the shadow FBO should be punched to alpha=0:
     *  • Space / void / empty tiles (no ground = no shadow plane).
     *  • Glowing liquid tiles with no solid building on top.
     */
    private static boolean shouldEraseShadow(Tile t) {
        if (t == null) return true; // off-world treated as space
        // A solid building blocks the glow — keep shadow on top of it
        if (t.build != null && t.block().solid) return false;
        Floor fl = t.floor();
        if (fl == null) return false;
        String n = fl.name.toLowerCase();
        // Space / void tiles
        if (fl == mindustry.content.Blocks.space || fl == mindustry.content.Blocks.empty
                || n.contains("space") || n.contains("void")
                || n.contains("empty") || n.contains("dark-panel")) return true;
        // Glowing liquid floors
        return n.contains("slag") || n.contains("lava") || n.contains("magma") || n.contains("hot") || n.contains("cryo");
    }

    // 16x16 Chunk-Based Static Caster Cache
    public static class ChunkCache {
        public static final int CHUNK_SIZE = 16;
        public static CasterChunk[][] chunks;
        public static int mapW = 0, mapH = 0;
        public static boolean initialized = false;

        public static class CasterChunk {
            public final arc.struct.Seq<CasterEntry> casters = new arc.struct.Seq<>(false, 8);
            public boolean valid = false;
        }

        public static class CasterEntry {
            public int x, y;
            public float cx, cy;
            public float size;
            public float elev;
            public float mod;
            public boolean isProp;

            // For props
            public Block block;
            public TextureRegion region;
            public AnyBlocksShadows.PropShadowType propType;
        }

        public static void init() {
            if (Vars.world == null) return;
            mapW = (int) Math.ceil(Vars.world.width() / (double) CHUNK_SIZE);
            mapH = (int) Math.ceil(Vars.world.height() / (double) CHUNK_SIZE);
            chunks = new CasterChunk[mapW][mapH];
            for (int x = 0; x < mapW; x++) {
                for (int y = 0; y < mapH; y++) {
                    chunks[x][y] = new CasterChunk();
                }
            }
            initialized = true;
        }

        public static void invalidateAll() {
            if (!initialized || chunks == null) return;
            for (int x = 0; x < mapW; x++) {
                for (int y = 0; y < mapH; y++) {
                    chunks[x][y].valid = false;
                }
            }
        }

        public static void invalidateTile(int x, int y) {
            if (!initialized || chunks == null) return;
            int cx = x / CHUNK_SIZE;
            int cy = y / CHUNK_SIZE;
            if (cx >= 0 && cx < mapW && cy >= 0 && cy < mapH) {
                chunks[cx][cy].valid = false;
            }
        }

        public static void rebuildChunk(int cx, int cy) {
            if (!initialized || chunks == null) return;
            CasterChunk chunk = chunks[cx][cy];
            chunk.casters.clear();

            int startX = cx * CHUNK_SIZE;
            int startY = cy * CHUNK_SIZE;
            int endX = Math.min(startX + CHUNK_SIZE - 1, Vars.world.width() - 1);
            int endY = Math.min(startY + CHUNK_SIZE - 1, Vars.world.height() - 1);

            for (int x = startX; x <= endX; x++) {
                for (int y = startY; y <= endY; y++) {
                    Tile tile = Vars.world.tile(x, y);
                    if (tile == null) continue;

                    Floor fl = tile.floor();
                    if (isLuminousFloor(fl)) continue;

                    boolean isBuild = tile.build != null && tile.isCenter();
                    boolean isProp  = !oldShadowsEnabled && !isBuild 
                                      && (tile.block() instanceof mindustry.world.blocks.environment.Prop 
                                          || tile.block() instanceof mindustry.world.blocks.environment.TreeBlock)
                                      && !(tile.block() instanceof mindustry.world.blocks.environment.StaticWall);
                    boolean isWall  = !isBuild && !isProp && tile.block().solid && !isBuriedWall(x, y);
                    if (!isBuild && !isWall && !isProp) continue;
                    if (isBuild && tile.block().size < 2 && tile.build.block.emitLight && tile.build.block.lightRadius > 30f) continue;

                    CasterEntry e = new CasterEntry();
                    e.x = x;
                    e.y = y;
                    e.cx = isBuild ? tile.build.x : tile.worldx();
                    e.cy = isBuild ? tile.build.y : tile.worldy();
                    e.size = (isBuild ? tile.build.block.size * Vars.tilesize : Vars.tilesize) + 0.5f;
                    e.elev = isBuild ? getElev(tile.build.block, 1f) : getElev(tile.block(), 1.6f);
                    e.mod = AnyBlocksShadows.getModifier(isBuild ? tile.build.block : tile.block());
                    e.isProp = isProp;

                    if (isProp) {
                        Block block = tile.block();
                        TextureRegion region = block.region;
                        if (block.variants > 0 && block.variantRegions != null && block.variantRegions.length > 0) {
                            int index = Mathf.randomSeed(tile.pos(), 0, block.variantRegions.length - 1);
                            if (index >= 0 && index < block.variantRegions.length
                                    && block.variantRegions[index] != null
                                    && block.variantRegions[index].found()) {
                                region = block.variantRegions[index];
                            }
                        }
                        if (region == null || !region.found()) continue;

                        e.block = block;
                        e.region = region;
                        e.propType = AnyBlocksShadows.getPropType(block, region);
                    }

                    chunk.casters.add(e);
                }
            }
            chunk.valid = true;
        }
    }
}
