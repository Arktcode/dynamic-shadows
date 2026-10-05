package dynamicShadows;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.GL20;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

public class DynamicShadowRenderer {
    public static float BASE_SHADOW_ANGLE = 210f, SHADOW_LENGTH = 10f, SHADOW_ALPHA = 0.38f, weatherMult = 1f;
    public static boolean enabled = true, dayNightCycle = true, zoomFadeEnabled = true, rotateShadows = true;
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

    /** 0 = Baja (50%), 1 = Media (75%), 2 = Alta (100%, por defecto) */
    public static int graphicsQuality = 2;

    // FrameBuffers por Tier para el pipeline de sombras en capas Z
    private static final FrameBuffer[] tierFbo = new FrameBuffer[ShadowLayerConfig.numTiers];
    private static final FrameBuffer[] tierFbo2 = new FrameBuffer[ShadowLayerConfig.numTiers];
    private static final FrameBuffer[] tierFbo3 = new FrameBuffer[ShadowLayerConfig.numTiers];

    private static final TextureRegion[] tierReg = new TextureRegion[ShadowLayerConfig.numTiers];
    private static final TextureRegion[] tierReg2 = new TextureRegion[ShadowLayerConfig.numTiers];
    private static final TextureRegion[] tierReg3 = new TextureRegion[ShadowLayerConfig.numTiers];

    // Caché del FBO: se redibuja solo cuando hay cambios
    public static volatile boolean shadDirty = true;
    private static float lastCachedAngle = -999f;
    private static float lastCachedCamX = 0f, lastCachedCamY = 0f, lastCachedCamW = 0f;

    private static ShadowShader shadowShader;
    private static final ObjectFloatMap<Block> elevCache = new ObjectFloatMap<>(64);
    private static TextureRegion emptyRegion;
    private static float lastFboScale = -1f;

    public static float currentSunElevation = 0f, currentCycleProgress = 0f;
    private static final ObjectFloatMap<Integer> bridgeWarmupMap = new ObjectFloatMap<>();
    private static final ObjectMap<Integer, float[]> bridgePosMap = new ObjectMap<>();
    private static final boolean[] tierHasContent = new boolean[ShadowLayerConfig.numTiers];

    public static class BridgeLinkData {
        public float x1, y1, x2, y2, warmup;
    }
    private static final Seq<BridgeLinkData> bridgeLinks = new Seq<>(32);
    private static final Seq<BridgeLinkData> bridgeLinkPool = new Seq<>(32);
    private static final IntSet activeBridgeIds = new IntSet();
    private static final IntSeq toRemoveBridgeIds = new IntSeq();

    private static BridgeLinkData obtainBridgeLink() {
        if (bridgeLinkPool.size > 0) return bridgeLinkPool.pop();
        return new BridgeLinkData();
    }

    private static void freeBridgeLinks() {
        bridgeLinkPool.addAll(bridgeLinks);
        bridgeLinks.clear();
    }

    public static class UnitShadowData {
        public TextureRegion region;
        public int tier;
        public float x, y, w, h, rotation, alpha;
    }
    private static final Seq<UnitShadowData> unitShadows = new Seq<>(64);
    private static final Seq<UnitShadowData> unitShadowPool = new Seq<>(64);

    private static UnitShadowData obtainUnitShadow() {
        if (unitShadowPool.size > 0) return unitShadowPool.pop();
        return new UnitShadowData();
    }

    private static void freeUnitShadows() {
        unitShadowPool.addAll(unitShadows);
        unitShadows.clear();
    }

    public static float getTierZoomFade(int tier, float tilePixels) {
        if (!zoomFadeEnabled) return 1f;
        switch (tier) {
            case ShadowLayerConfig.tierSmall:
                return Mathf.clamp((tilePixels - 10f) / (18f - 10f), 0f, 1f);
            case ShadowLayerConfig.tierMed:
                return Mathf.clamp((tilePixels - 8f) / (14f - 8f), 0f, 1f);
            case ShadowLayerConfig.tierLarge:
                return Mathf.clamp((tilePixels - 7f) / (12f - 7f), 0f, 1f);
            default:
                return Mathf.clamp((tilePixels - 6.1f) / (11.1f - 6.1f), 0f, 1f);
        }
    }

    public static void queue() {
        if (!enabled || Vars.headless || !Vars.state.isGame()) return;
        if (emptyRegion == null) emptyRegion = Core.atlas.find("clear");

        final float rotTicks    = 1620f * 60f;
        final float cycleProgress = rotateShadows ? (arc.util.Time.time / rotTicks) % 1f : 0f;
        currentCycleProgress    = cycleProgress;

        final float angle = BASE_SHADOW_ANGLE + cycleProgress * 360f;
        final float cosA  = Mathf.cosDeg(angle);
        final float sinA  = Mathf.sinDeg(angle);

        float rawSun = Mathf.sin(cycleProgress * Mathf.PI2);
        currentSunElevation = rawSun;

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

        float darkFade = 1f;
        if (darkness > darkFadeThreshold) {
            float depth = Mathf.clamp((darkness - darkFadeThreshold) / (1f - darkFadeThreshold), 0f, 1f);
            darkFade = 1f - depth * darkFadeStrength;
        }

        final float shadowScale = SHADOW_LENGTH * Vars.tilesize * (1f + darkness * 1.2f);
        final float alpha       = SHADOW_ALPHA * Mathf.clamp(weatherMult) * (1f - darkness * 0.5f) * darkFade;

        final float camX = Core.camera.position.x, camY = Core.camera.position.y;
        final float camW = Core.camera.width,       camH = Core.camera.height;
        float margin  = shadowScale + Vars.tilesize * 4f;
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

        final float fboScale = qualityScale();

        int gw = Core.graphics.getWidth(), gh = Core.graphics.getHeight();
        int maxFboDim = Vars.mobile ? 1920 : 3840;
        int fw = Mathf.clamp((int)(gw * fboScale), 1, maxFboDim);
        int fh = Mathf.clamp((int)(gh * fboScale), 1, maxFboDim);

        if (tierFbo[0] == null || tierFbo[0].getWidth() != fw || tierFbo[0].getHeight() != fh
                || tierFbo[0].getTexture() == null || tierFbo[0].getTexture().getTextureObjectHandle() == 0
                || lastFboScale != fboScale) {
            disposeFBOs();
            lastFboScale = fboScale;
            try {
                for (int t = 0; t < ShadowLayerConfig.numTiers; t++) {
                    tierFbo[t]  = new FrameBuffer(fw, fh); tierReg[t]  = flipped(tierFbo[t]);
                    tierFbo2[t] = new FrameBuffer(fw, fh); tierReg2[t] = flipped(tierFbo2[t]);
                    tierFbo3[t] = new FrameBuffer(fw, fh); tierReg3[t] = flipped(tierFbo3[t]);

                    tierFbo[t].getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                    tierFbo2[t].getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                    tierFbo3[t].getTexture().setFilter(arc.graphics.Texture.TextureFilter.linear);
                }
            } catch (Exception e) { return; }
        }

        if (!ChunkCache.initialized) {
            ChunkCache.init();
        }

        int chX1 = tx1 / ChunkCache.CHUNK_SIZE;
        int chY1 = ty1 / ChunkCache.CHUNK_SIZE;
        int chX2 = tx2 / ChunkCache.CHUNK_SIZE;
        int chY2 = ty2 / ChunkCache.CHUNK_SIZE;

        final float ts = Vars.tilesize;

        // Limpiar pools de la iteración previa
        freeUnitShadows();
        freeBridgeLinks();

        // Recolectar enlaces de puentes visibles
        activeBridgeIds.clear();
        final boolean[] anyBridgeAnimating = {false};

        mindustry.gen.Groups.build.each(b -> {
            if (b.x < screenX1 || b.x > screenX2 || b.y < screenY1 || b.y > screenY2) return;
            if (!(b instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)) return;
            mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild bridge = (mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild) b;
            int linkPos = bridge.link;
            if (linkPos != -1) {
                int lx = arc.math.geom.Point2.x(linkPos);
                int ly = arc.math.geom.Point2.y(linkPos);
                mindustry.world.Tile tgt = Vars.world.tile(lx, ly);
                if (tgt != null && tgt.build != null && Mathf.dst(b.x, b.y, tgt.build.x, tgt.build.y) <= 12f * Vars.tilesize) {
                    activeBridgeIds.add(b.id);
                    float curWarmup = bridgeWarmupMap.get(b.id, 0f);
                    float newWarmup = Mathf.approachDelta(curWarmup, 1f, 0.08f);
                    if (Math.abs(newWarmup - curWarmup) > 0.001f) {
                        anyBridgeAnimating[0] = true;
                    }
                    bridgeWarmupMap.put(b.id, newWarmup);
                    float[] pos = bridgePosMap.get(b.id);
                    if (pos == null) {
                        pos = new float[4];
                        bridgePosMap.put(b.id, pos);
                        anyBridgeAnimating[0] = true;
                    }
                    pos[0] = b.x; pos[1] = b.y; pos[2] = tgt.build.x; pos[3] = tgt.build.y;

                    float animTx = Mathf.lerp(b.x, tgt.build.x, newWarmup);
                    float animTy = Mathf.lerp(b.y, tgt.build.y, newWarmup);
                    BridgeLinkData link = obtainBridgeLink();
                    link.x1 = b.x; link.y1 = b.y; link.x2 = animTx; link.y2 = animTy; link.warmup = newWarmup;
                    bridgeLinks.add(link);
                }
            }
        });

        // Animar puentes que perdieron su enlace (animación de salida)
        toRemoveBridgeIds.clear();
        for (ObjectMap.Entry<Integer, float[]> entry : bridgePosMap) {
            int bid = entry.key;
            if (!activeBridgeIds.contains(bid)) {
                float curWarmup = bridgeWarmupMap.get(bid, 0f);
                float newWarmup = Mathf.approachDelta(curWarmup, 0f, 0.08f);
                if (Math.abs(newWarmup - curWarmup) > 0.001f) {
                    anyBridgeAnimating[0] = true;
                }
                float[] pos = entry.value;
                if (newWarmup > 0.001f && pos != null && Mathf.dst(pos[0], pos[1], pos[2], pos[3]) <= 12f * Vars.tilesize) {
                    bridgeWarmupMap.put(bid, newWarmup);
                    float animTx = Mathf.lerp(pos[0], pos[2], newWarmup);
                    float animTy = Mathf.lerp(pos[1], pos[3], newWarmup);
                    BridgeLinkData link = obtainBridgeLink();
                    link.x1 = pos[0]; link.y1 = pos[1]; link.x2 = animTx; link.y2 = animTy; link.warmup = newWarmup;
                    bridgeLinks.add(link);
                } else {
                    toRemoveBridgeIds.add(bid);
                    anyBridgeAnimating[0] = true;
                }
            }
        }
        for (int i = 0; i < toRemoveBridgeIds.size; i++) {
            int bid = toRemoveBridgeIds.get(i);
            bridgeWarmupMap.remove(bid, 0f);
            bridgePosMap.remove(bid);
        }

        // Recolectar datos de sombras de unidades fuera del lambda reutilizando el pool
        if (unitShadowsEnabled) {
            mindustry.gen.Groups.unit.each(u -> {
                if (u.x < screenX1 || u.x > screenX2 || u.y < screenY1 || u.y > screenY2) return;
                TextureRegion usRegion = AnyBlocksShadows.getOriginalShadow(u.type);
                if (usRegion == null || !usRegion.found()) return;
                int uTier = ShadowLayerConfig.unitTier(u.elevation);
                float ufl = shadowScale * (1f + u.elevation * 2.8f) * 0.03f;
                float uw = usRegion.width * Draw.scl;
                float uh = usRegion.height * Draw.scl;
                float sizeMult = 1f + u.elevation * 0.22f;
                float alphaMult = Mathf.clamp(1f - u.elevation * 0.50f, 0.25f, 1f);
                UnitShadowData d = obtainUnitShadow();
                d.region = usRegion;
                d.tier = uTier;
                d.x = u.x + cosA * ufl;
                d.y = u.y + sinA * ufl;
                d.w = uw * sizeMult;
                d.h = uh * sizeMult;
                d.rotation = u.rotation;
                d.alpha = alphaMult;
                unitShadows.add(d);
            });
        }

        final float currentPpu = (float) Core.graphics.getWidth() / Core.camera.width;
        float camMoveThreshold = Vars.mobile ? Math.max(0.20f, camW * 0.003f) : Math.max(0.08f, camW * 0.0015f);
        float camZoomThreshold = Vars.mobile ? Math.max(0.30f, camW * 0.004f) : Math.max(0.15f, camW * 0.0025f);

        // Redibujar el FBO si la cámara se movió, el mapa cambió o hay puentes en animación
        final boolean needsRedraw = shadDirty
            || anyBridgeAnimating[0]
            || Math.abs(angle - lastCachedAngle) > 0.5f
            || Math.abs(camX - lastCachedCamX) > camMoveThreshold
            || Math.abs(camY - lastCachedCamY) > camMoveThreshold
            || Math.abs(camW - lastCachedCamW) > camZoomThreshold;

        if (needsRedraw) {
            shadDirty = false;
            lastCachedAngle = angle;
            lastCachedCamX = camX;
            lastCachedCamY = camY;
            lastCachedCamW = camW;
        }

        final float rawTilePixels = (Vars.tilesize / Core.camera.width) * Core.graphics.getWidth();
        float minTilePixels = 6.0f;
        if (Vars.renderer != null && Vars.renderer.minScale() > 0.001f) {
            minTilePixels = Vars.tilesize * Vars.renderer.minScale();
        }
        final float tilePixels = minTilePixels > 0.001f ? (rawTilePixels / minTilePixels) * 6.0f : rawTilePixels;

        // Pipeline de sombras en 5 Tiers
        for (int t = 0; t < ShadowLayerConfig.numTiers; t++) {
            final int tier = t;
            final float drawZ = Layers.getZ(tier);

            Draw.draw(drawZ, () -> {
                float tierFade = getTierZoomFade(tier, tilePixels);

                if (needsRedraw) {
                    if (tierFade <= 0.005f) {
                        tierHasContent[tier] = false;
                        return; // Omitir FBO y shaders por completo si este Tier está desvanecido al alejar la cámara
                    }

                    // Descarte de Tiers Vacíos (Empty Tier Skipping):
                    boolean hasCasters = false;
                    for (int cx = chX1; cx <= chX2 && !hasCasters; cx++) {
                        for (int cy = chY1; cy <= chY2; cy++) {
                            if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                            ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                            if (chunk == null || !chunk.valid || chunk.tierCasters[tier].size > 0) {
                                hasCasters = true;
                                break;
                            }
                        }
                    }
                    if (tier == ShadowLayerConfig.tierXL && !bridgeLinks.isEmpty()) {
                        hasCasters = true;
                    }

                    tierHasContent[tier] = hasCasters;
                    if (!hasCasters) {
                        return; // Omitir FBO, shaders y limpiezas de este Tier vacío
                    }

                    tierFbo[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                Draw.color(0.04f, 0.03f, 0.08f);

                for (int cx = chX1; cx <= chX2; cx++) {
                    for (int cy = chY1; cy <= chY2; cy++) {
                        if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                        ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                        if (!chunk.valid) {
                            ChunkCache.rebuildChunkSync(cx, cy);
                            chunk = ChunkCache.chunks[cx][cy];
                        }

                        Seq<ChunkCache.CasterEntry> list = chunk.tierCasters[tier];
                        for (int i = 0; i < list.size; i++) {
                            ChunkCache.CasterEntry e = list.get(i);
                            if (e.mod == 0f) continue;

                            float whs = e.size * 0.5f + 1f;
                            float bx1 = Math.min(e.cx - whs, e.cx - whs + cosA * shadowScale);
                            float by1 = Math.min(e.cy - whs, e.cy - whs + sinA * shadowScale);
                            float bx2 = Math.max(e.cx + whs, e.cx + whs + cosA * shadowScale);
                            float by2 = Math.max(e.cy + whs, e.cy + whs + sinA * shadowScale);
                            if (bx2 < screenX1 || bx1 > screenX2 || by2 < screenY1 || by1 > screenY2) continue;

                            // Solo dibujar montañas cuya cara apunta hacia el exterior
                            if (tier == ShadowLayerConfig.TIER_ENV && !e.isProp) {
                                if (!isExposedMountainCaster(e.x, e.y, cosA, sinA)) continue;
                            }

                            if (e.isProp) {
                                float propH = e.region.height * Draw.scl;
                                float propLen = propH * (shadowScale / 80f) * 1.35f * propShadowScale;
                                if (propLen < 0.4f) continue;
                                float propContactAlpha = contactShadow * 0.55f * fDarkFade;
                                AnyBlocksShadows.drawPropShadow(
                                        e.cx, e.cy, e.region, e.propType, propLen,
                                        cosA, sinA, angle, propContactAlpha, 1f);
                            } else {
                                float fLen = shadowScale * e.elev * e.mod;
                                if (fLen < 0.2f) continue;

                                if (contactShadow > 0f) {
                                    Draw.color(0.02f, 0.015f, 0.04f, contactShadow * 0.70f);
                                    float rectSize = e.rawSize;
                                    Fill.rect(e.cx, e.cy, rectSize, rectSize);
                                }
                                Draw.color(0.04f, 0.03f, 0.08f, 1f);
                                AnyBlocksShadows.draw(e.cx, e.cy, fLen, cosA, sinA, e.region, e.rawSize);
                            }
                        }
                    }
                }

                // Sombras de enlaces de puente en Tier XL (sobre bloques 1x1 a 5x5)
                if (tier == ShadowLayerConfig.tierXL && !bridgeLinks.isEmpty()) {
                    float bridgeFLen = shadowScale * 0.025f;
                    for (int i = 0; i < bridgeLinks.size; i++) {
                        BridgeLinkData lk = bridgeLinks.get(i);
                        drawBridgeLinkShadow(lk.x1, lk.y1, lk.x2, lk.y2, bridgeFLen, cosA, sinA, lk.warmup);
                    }
                }

                // Borrar la huella del bloque para evitar autosombra
                Draw.flush();
                Draw.blend(arc.graphics.Blending.disabled);
                Draw.color(0f, 0f, 0f, 0f);
                eraseTierFootprints(chX1, chY1, chX2, chY2, tier, screenX1, screenY1, screenX2, screenY2);

                // Borrar paredes rocosas en todos los tiers que dibujan sobre Z=30f para evitar sombras sobre montañas
                if (tier >= ShadowLayerConfig.tierLarge) {
                    eraseWallTiles(chX1, chY1, chX2, chY2, screenX1, screenY1, screenX2, screenY2);
                }

                // Borra casillas de suelo luminoso, líquido o espacio Ñ
                eraseFloorTiles(chX1, chY1, chX2, chY2, screenX1, screenY1, screenX2, screenY2);

                Draw.flush();
                Draw.blend(arc.graphics.Blending.normal);
                tierFbo[tier].end();

                // Pase de desenfoque horizontal
                tierFbo2[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                applyShaderPass(1f, 0f, fSunElev, tierReg[tier], camX, camY, camW, camH);
                Draw.flush();
                tierFbo2[tier].end();

                // Pase de desenfoque vertical
                tierFbo3[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                applyShaderPass(0f, 1f, fSunElev, tierReg2[tier], camX, camY, camW, camH);

                // Re-borrar huellas tras el desenfoque para mantener techos limpios
                Draw.flush();
                Draw.blend(arc.graphics.Blending.disabled);
                Draw.color(0f, 0f, 0f, 0f);
                eraseTierFootprints(chX1, chY1, chX2, chY2, tier, screenX1, screenY1, screenX2, screenY2);
                if (tier >= ShadowLayerConfig.tierLarge) {
                    eraseWallTiles(chX1, chY1, chX2, chY2, screenX1, screenY1, screenX2, screenY2);
                }
                Draw.flush();
                Draw.blend(arc.graphics.Blending.normal);

                tierFbo3[tier].end();
                }

                // Dibujar la textura final desenfocada en la posición de la cámara (solo si el Tier tiene contenido y está visible)
                if (enabled && tierHasContent[tier] && tierFade > 0.005f && tierReg3[tier] != null && tierReg3[tier].texture != null) {
                    Draw.color(Color.white, alpha * tierFade);
                    Draw.rect(tierReg3[tier], camX, camY, camW, camH);
                    Draw.color();
                }

                // Dibujar sombras de unidades encima del FBO
                if (!unitShadows.isEmpty() && tierFade > 0.005f) {
                    for (int i = 0; i < unitShadows.size; i++) {
                        UnitShadowData ud = unitShadows.get(i);
                        if (ud.tier != tier) continue;
                        Draw.color(0.04f, 0.03f, 0.08f, ud.alpha * alpha * tierFade);
                        Draw.rect(ud.region, ud.x, ud.y, ud.w, ud.h, ud.rotation - 90);
                    }
                    Draw.color();
                }
            });
        }

        // Dibujar árboles (StaticTree como sporePine) a Z=71.0f para asegurar que queden por encima de todas las sombras
        Draw.draw(Layers.treeLayer, () -> {
            for (int cx = chX1; cx <= chX2; cx++) {
                for (int cy = chY1; cy <= chY2; cy++) {
                    if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                    ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                    if (chunk == null || !chunk.valid || chunk.treeTiles.isEmpty()) continue;
                    IntSeq list = chunk.treeTiles;
                    for (int i = 0; i < list.size; i++) {
                        int pos = list.get(i);
                        int tx = Point2.x(pos);
                        int ty = Point2.y(pos);
                        Tile t = Vars.world.tile(tx, ty);
                        if (t != null && t.block() != null && t.block() instanceof mindustry.world.blocks.environment.StaticTree) {
                            t.block().drawBase(t);
                        }
                    }
                }
            }
        });

    }

    private static void eraseTierFootprints(int chX1, int chY1, int chX2, int chY2, int tier, float sX1, float sY1, float sX2, float sY2) {
        for (int cx = chX1; cx <= chX2; cx++) {
            for (int cy = chY1; cy <= chY2; cy++) {
                if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                if (!chunk.valid) continue;
                for (int t = (tier == ShadowLayerConfig.tierXL ? 0 : tier); t < ShadowLayerConfig.numTiers; t++) {
                    Seq<ChunkCache.CasterEntry> list = chunk.tierCasters[t];
                    for (int i = 0; i < list.size; i++) {
                        ChunkCache.CasterEntry e = list.get(i);
                        if (e.mod == 0f || e.isProp) continue;

                        // En Tier XL solo borrar la huella de bloques de puente en tiers inferiores
                        if (t < tier && !ShadowLayerConfig.isBridge(e.block)) continue;

                        float whs = e.size * 0.5f + 1f;
                        if (e.cx + whs < sX1 || e.cx - whs > sX2 || e.cy + whs < sY1 || e.cy - whs > sY2) continue;
                        float eraseSize = Math.max(0.1f, e.rawSize - 0.2f);
                        Fill.rect(e.cx, e.cy, eraseSize, eraseSize);
                    }
                }
            }
        }
    }

    private static void drawBridgeLinkShadow(float bx, float by, float tx, float ty,
                                              float fLen, float cosA, float sinA, float alphaMult) {
        float x1 = bx + cosA * fLen;
        float y1 = by + sinA * fLen;
        float x2 = tx + cosA * fLen;
        float y2 = ty + sinA * fLen;

        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;

        float nx = -dy / len * 3.5f;
        float ny =  dx / len * 3.5f;

        Draw.color(0.04f, 0.03f, 0.08f, alphaMult);
        Fill.quad(
            x1 + nx, y1 + ny,
            x1 - nx, y1 - ny,
            x2 - nx, y2 - ny,
            x2 + nx, y2 + ny
        );
    }

    private static void eraseWallTiles(int chX1, int chY1, int chX2, int chY2, float sX1, float sY1, float sX2, float sY2) {
        float ts = Vars.tilesize;
        for (int cx = chX1; cx <= chX2; cx++) {
            for (int cy = chY1; cy <= chY2; cy++) {
                if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                if (chunk == null || !chunk.valid || chunk.mountainTiles.isEmpty()) continue;
                IntSeq list = chunk.mountainTiles;
                for (int i = 0; i < list.size; i++) {
                    int pos = list.get(i);
                    float tx = Point2.x(pos) * ts;
                    float ty = Point2.y(pos) * ts;
                    if (tx + ts < sX1 || tx - ts > sX2 || ty + ts < sY1 || ty - ts > sY2) continue;
                    Fill.rect(tx, ty, ts, ts);
                }
            }
        }
    }

    private static void eraseFloorTiles(int chX1, int chY1, int chX2, int chY2, float sX1, float sY1, float sX2, float sY2) {
        for (int cx = chX1; cx <= chX2; cx++) {
            for (int cy = chY1; cy <= chY2; cy++) {
                if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                if (chunk == null || !chunk.valid || chunk.eraseFloorTiles.isEmpty()) continue;
                IntSeq list = chunk.eraseFloorTiles;
                for (int i = 0; i < list.size; i++) {
                    int p = list.get(i);
                    int x = Point2.x(p);
                    int y = Point2.y(p);
                    float wx = x * (float) 8.0;
                    float wy = y * (float) 8.0;
                    if (wx + (float) 8.0 < sX1 || wx - (float) 8.0 > sX2 || wy + (float) 8.0 < sY1 || wy - (float) 8.0 > sY2) continue;
                    Fill.rect(wx, wy, (float) 8.0 + 0.1f, (float) 8.0 + 0.1f);
                }
            }
        }
    }

    private static float qualityScale() {
        if (graphicsQuality == 0) return 0.50f;
        if (graphicsQuality == 1) return 0.75f;
        return 1.00f;
    }

    private static void applyShaderPass(float dx, float dy, float sunElev, TextureRegion src, float cx, float cy, float cw, float ch) {
        if (shadowShader == null) shadowShader = new ShadowShader();
        float scl = arc.scene.ui.layout.Scl.scl();
        if (scl <= 0.001f) scl = 1f;
        float currentPpu = ((float)Core.graphics.getWidth() / Core.camera.width) / scl;
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
        Draw.rect(src, cx, cy, cw, ch);
        Draw.flush();
        Draw.shader();
        Draw.blend(arc.graphics.Blending.normal);
    }

    private static TextureRegion flipped(FrameBuffer fb) {
        TextureRegion r = new TextureRegion(fb.getTexture()); r.flip(false, true); return r;
    }

    private static void disposeFBOs() {
        for (int t = 0; t < ShadowLayerConfig.numTiers; t++) {
            tierHasContent[t] = false;
            for (FrameBuffer b : new FrameBuffer[]{tierFbo[t], tierFbo2[t], tierFbo3[t]}) {
                if (b != null) try { b.dispose(); } catch (Exception ignored) {}
            }
            tierFbo[t] = tierFbo2[t] = tierFbo3[t] = null;
        }
    }

    private static boolean isSolidAt(int x, int y) {
        if (Vars.world == null) return false;
        if (x < 0 || y < 0 || x >= Vars.world.width() || y >= Vars.world.height()) return true;
        Tile t = Vars.world.tile(x, y);
        if (t == null) return true;
        if (t.build != null) return t.build.block.solid;
        return t.block().solid;
    }

    private static boolean isBuriedTile(int x, int y) {
        return isSolidAt(x + 1, y) && isSolidAt(x - 1, y) && isSolidAt(x, y + 1) && isSolidAt(x, y - 1);
    }

    private static boolean isBuriedMountain(int x, int y) {
        return isSolidAt(x + 1, y) && isSolidAt(x - 1, y) && isSolidAt(x, y + 1) && isSolidAt(x, y - 1);
    }

    // Devuelve true si la montaña tiene al menos una cara expuesta en la dirección de la sombra
    private static boolean isExposedMountainCaster(int x, int y, float cosA, float sinA) {
        if (cosA > 0.01f && !isSolidAt(x + 1, y)) return true;
        if (cosA < -0.01f && !isSolidAt(x - 1, y)) return true;
        if (sinA > 0.01f && !isSolidAt(x, y + 1)) return true;
        return sinA < -0.01f && !isSolidAt(x, y - 1);
    }

    private static float getElev(Block b, float def) {
        synchronized (elevCache) {
            if (elevCache.containsKey(b)) return elevCache.get(b, def);
        }
        float v = def;
        try { v = b.getClass().getField("shadowElevation").getFloat(b); } catch (Exception ignored){}
        synchronized (elevCache) {
            elevCache.put(b, v);
        }
        return v;
    }

    private static boolean isLuminousFloor(Floor floor) {
        if (floor == null || floor.name == null) return false;
        String n = floor.name.toLowerCase();
        return n.contains("slag") || n.contains("lava") || n.contains("magma") || n.contains("hot") || n.contains("cryo");
    }

    private static final ObjectMap<Block, Boolean> floorEraseCache = new ObjectMap<>(64);

    private static boolean shouldEraseShadow(Tile t) {
        if (t == null) return true;
        if (t.build != null && t.block().solid) return false;
        Floor fl = t.floor();
        if (fl == null) return false;
        Boolean cached = floorEraseCache.get(fl);
        if (cached != null) return cached;

        if (fl == mindustry.content.Blocks.space || fl == mindustry.content.Blocks.empty) {
            floorEraseCache.put(fl, true);
            return true;
        }

        String n = fl.name != null ? fl.name.toLowerCase() : "";

        // metal-floor-6, metal-floor-12 y runa crux no reciben sombras, ya que obviamente quedaria raro
        boolean isMetal6  = n.contains("metal-floor-6")  || n.contains("metal-tile-6")  || n.contains("metal6")  || (n.endsWith("-6")  && n.contains("metal"));
        boolean isMetal12 = n.contains("metal-floor-12") || n.contains("metal-tile-12") || n.contains("metal12") || (n.endsWith("-12") && n.contains("metal"));
        boolean isCruxRune = n.contains("crux") || n.contains("rune");

        if (isMetal6 || isMetal12 || isCruxRune) {
            floorEraseCache.put(fl, true);
            return true;
        }

        boolean res = n.contains("space") || n.contains("void") || n.contains("empty")
                   || n.contains("slag") || n.contains("lava") || n.contains("magma") || n.contains("hot") || n.contains("cryo");

        floorEraseCache.put(fl, res);
        return res;
    }


    // Caché de proyectores estáticos organizado en chunks de 16x16 y 5 Tiers (un ligero intento de rendimiento)
    public static class ChunkCache {
        public static final int CHUNK_SIZE = 16;
        public static volatile CasterChunk[][] chunks;
        public static int mapW = 0, mapH = 0;
        public static boolean initialized = false;

        private static java.util.concurrent.ExecutorService threadPool;
        private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> pendingChunks = new java.util.concurrent.ConcurrentHashMap<>();

        public static class CasterChunk {
            public final Seq<CasterEntry>[] tierCasters = new Seq[ShadowLayerConfig.numTiers];
            public final IntSeq eraseFloorTiles = new IntSeq();
            public final IntSeq mountainTiles = new IntSeq();
            public final IntSeq treeTiles = new IntSeq();
            public boolean valid = false;

            public CasterChunk() {
                for (int i = 0; i < ShadowLayerConfig.numTiers; i++) {
                    tierCasters[i] = new Seq<>(false, 4);
                }
            }
        }

        public static class CasterEntry {
            public int x, y;
            public float cx, cy;
            public float rawSize;
            public float size;
            public float elev;
            public float mod;
            public boolean isProp;
            public int tier;

            public Block block;
            public TextureRegion region;
            public AnyBlocksShadows.PropShadowType propType;
        }

        public static void init() {
            if (Vars.world == null) return;
            if (threadPool != null && !threadPool.isShutdown()) {
                threadPool.shutdownNow();
            }
            int threads = Mathf.clamp(Runtime.getRuntime().availableProcessors(), 2, 8);
            threadPool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            pendingChunks.clear();

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
            bridgeWarmupMap.clear();
            bridgePosMap.clear();
            pendingChunks.clear();
            DynamicShadowRenderer.shadDirty = true;
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
                rebuildChunkSync(cx, cy);
                DynamicShadowRenderer.shadDirty = true;
            }
        }

        public static void requestRebuildAsync(int cx, int cy) {
            if (!initialized || threadPool == null || threadPool.isShutdown()) return;
            long key = (((long) cx) << 32) | (cy & 0xFFFFFFFFL);
            if (pendingChunks.putIfAbsent(key, Boolean.TRUE) != null) return;

            threadPool.submit(() -> {
                try {
                    rebuildChunkSync(cx, cy);
                } catch (Exception ignored) {
                } finally {
                    pendingChunks.remove(key);
                }
            });
        }

        public static void rebuildChunk(int cx, int cy) {
            rebuildChunkSync(cx, cy);
        }

        public static void rebuildChunkSync(int cx, int cy) {
            if (!initialized || Vars.world == null) return;
            CasterChunk newChunk = new CasterChunk();

            int startX = cx * CHUNK_SIZE;
            int startY = cy * CHUNK_SIZE;
            int endX = Math.min(startX + CHUNK_SIZE - 1, Vars.world.width() - 1);
            int endY = Math.min(startY + CHUNK_SIZE - 1, Vars.world.height() - 1);

            for (int x = startX; x <= endX; x++) {
                for (int y = startY; y <= endY; y++) {
                    Tile tile = Vars.world.tile(x, y);
                    if (tile == null) continue;

                    if (shouldEraseShadow(tile)) {
                        newChunk.eraseFloorTiles.add(Point2.pack(x, y));
                    }

                    Floor fl = tile.floor();
                    if (isLuminousFloor(fl)) continue;

                    boolean isBuild = tile.build != null && tile.isCenter();
                    boolean isPine  = !isBuild && AnyBlocksShadows.isPine(tile.block());
                    boolean isTree  = !isBuild && ShadowLayerConfig.isTree(tile.block());
                    boolean isCrystal = !isBuild && ShadowLayerConfig.isCrystal(tile.block());

                    boolean isMtnBlock = !isBuild && tile.block().solid && (ShadowLayerConfig.isMountainOrWall(tile.block()) || isPine);
                    if (isMtnBlock) {
                        newChunk.mountainTiles.add(Point2.pack(x, y));
                    }
                    if (isTree) {
                        newChunk.treeTiles.add(Point2.pack(x, y));
                    }

                    boolean isProp  = !oldShadowsEnabled && !isBuild && !isPine && !isMtnBlock
                                      && (tile.block() instanceof mindustry.world.blocks.environment.Prop
                                          || tile.block() instanceof mindustry.world.blocks.environment.TreeBlock
                                          || tile.block() instanceof mindustry.world.blocks.environment.TallBlock
                                          || isCrystal || isTree)
                                      && !(tile.block() instanceof mindustry.world.blocks.environment.StaticWall);
                    boolean isMtn   = !isBuild && !isProp && tile.block().solid && isMtnBlock;
                    boolean isWall  = !isBuild && !isProp && tile.block().solid
                                      && (isMtn ? !isBuriedMountain(x, y) : !isBuriedTile(x, y));
                    if (!isBuild && !isWall && !isProp) continue;
                    if (isBuild && tile.build.block instanceof mindustry.world.blocks.power.LightBlock) continue;

                    Block blk = isBuild ? tile.build.block : tile.block();
                    int tier = ShadowLayerConfig.getTier(blk);

                    CasterEntry e = new CasterEntry();
                    e.x = x;
                    e.y = y;
                    e.cx = isBuild ? tile.build.x : tile.worldx();
                    e.cy = isBuild ? tile.build.y : tile.worldy();
                    e.rawSize = isBuild ? tile.build.block.size * Vars.tilesize : Vars.tilesize;
                    // Montañas usan traslape extra (+2.0f) para eliminar costuras de rasterizado subpíxel
                    e.size = e.rawSize + (isMtn ? 2.0f : 0.4f);
                    e.elev = isBuild ? getElev(tile.build.block, 1f) : getElev(tile.block(), 1.6f);
                    e.mod = AnyBlocksShadows.getModifier(blk);
                    e.isProp = isProp;
                    e.tier = tier;
                    e.block = blk;

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

                    newChunk.tierCasters[tier].add(e);
                }
            }
            newChunk.valid = true;
            if (cx >= 0 && cx < mapW && cy >= 0 && cy < mapH) {
                chunks[cx][cy] = newChunk;
                DynamicShadowRenderer.shadDirty = true;
            }
        }
    }
}
