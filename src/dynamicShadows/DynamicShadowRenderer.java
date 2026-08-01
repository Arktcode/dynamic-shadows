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
    public static boolean enabled = true, dayNightCycle = true, rotateShadows = true;
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

    // 5-Tier FrameBuffers for Z-Layer Shadow Pipeline
    private static FrameBuffer[] tierFbo = new FrameBuffer[ShadowLayerConfig.NUM_TIERS];
    private static FrameBuffer[] tierFbo2 = new FrameBuffer[ShadowLayerConfig.NUM_TIERS];
    private static FrameBuffer[] tierFbo3 = new FrameBuffer[ShadowLayerConfig.NUM_TIERS];

    private static TextureRegion[] tierReg = new TextureRegion[ShadowLayerConfig.NUM_TIERS];
    private static TextureRegion[] tierReg2 = new TextureRegion[ShadowLayerConfig.NUM_TIERS];
    private static TextureRegion[] tierReg3 = new TextureRegion[ShadowLayerConfig.NUM_TIERS];

    private static ShadowShader shadowShader;
    private static final ObjectFloatMap<Block> elevCache = new ObjectFloatMap<>(64);
    private static TextureRegion emptyRegion;
    private static float lastFboScale = -1f;

    public static float currentSunElevation = 0f, currentCycleProgress = 0f;
    private static final ObjectFloatMap<Integer> bridgeWarmupMap = new ObjectFloatMap<>();
    private static final ObjectMap<Integer, float[]> bridgePosMap = new ObjectMap<>();

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

        final float shadowLen   = SHADOW_LENGTH * Vars.tilesize * (1f + darkness * 1.2f);
        final float shadowScale = shadowLen;
        final float alpha       = SHADOW_ALPHA * Mathf.clamp(weatherMult) * (1f - darkness * 0.5f) * darkFade;

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

        final float fboScale = qualityScale();

        int gw = Core.graphics.getWidth(), gh = Core.graphics.getHeight();
        int maxFboDim = 3840;
        int fw = Mathf.clamp((int)(gw * fboScale), 1, maxFboDim);
        int fh = Mathf.clamp((int)(gh * fboScale), 1, maxFboDim);

        if (tierFbo[0] == null || tierFbo[0].getWidth() != fw || tierFbo[0].getHeight() != fh
                || tierFbo[0].getTexture() == null || tierFbo[0].getTexture().getTextureObjectHandle() == 0
                || lastFboScale != fboScale) {
            disposeFBOs();
            lastFboScale = fboScale;
            try {
                for (int t = 0; t < ShadowLayerConfig.NUM_TIERS; t++) {
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

        // Recolectar puntos de enlace de puentes con animación de conexión y desconexión
        final java.util.ArrayList<float[]> bridgeLinks = new java.util.ArrayList<>();
        final IntSet activeBridgeIds = new IntSet();

        mindustry.gen.Groups.build.each(b -> {
            if (b.x < screenX1 || b.x > screenX2 || b.y < screenY1 || b.y > screenY2) return;
            if (!(b instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)) return;
            mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild bridge =
                    (mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild) b;
            int linkPos = bridge.link;
            if (linkPos != -1) {
                int lx = arc.math.geom.Point2.x(linkPos);
                int ly = arc.math.geom.Point2.y(linkPos);
                mindustry.world.Tile tgt = Vars.world.tile(lx, ly);
                if (tgt != null && tgt.build != null) {
                    activeBridgeIds.add(b.id);
                    float curWarmup = bridgeWarmupMap.get(b.id, 0f);
                    curWarmup = Mathf.approachDelta(curWarmup, 1f, 0.08f);
                    bridgeWarmupMap.put(b.id, curWarmup);
                    bridgePosMap.put(b.id, new float[]{b.x, b.y, tgt.build.x, tgt.build.y});

                    float animTx = Mathf.lerp(b.x, tgt.build.x, curWarmup);
                    float animTy = Mathf.lerp(b.y, tgt.build.y, curWarmup);
                    bridgeLinks.add(new float[]{b.x, b.y, animTx, animTy, curWarmup});
                }
            }
        });

        // Animar puentes en desconexión (cuyo enlace fue roto o el edificio removido)
        IntSeq toRemove = new IntSeq();
        for (ObjectMap.Entry<Integer, float[]> entry : bridgePosMap) {
            int bid = entry.key;
            if (!activeBridgeIds.contains(bid)) {
                float curWarmup = bridgeWarmupMap.get(bid, 0f);
                curWarmup = Mathf.approachDelta(curWarmup, 0f, 0.08f);
                if (curWarmup > 0.001f) {
                    bridgeWarmupMap.put(bid, curWarmup);
                    float[] pos = entry.value;
                    float animTx = Mathf.lerp(pos[0], pos[2], curWarmup);
                    float animTy = Mathf.lerp(pos[1], pos[3], curWarmup);
                    bridgeLinks.add(new float[]{pos[0], pos[1], animTx, animTy, curWarmup});
                } else {
                    toRemove.add(bid);
                }
            }
        }
        for (int i = 0; i < toRemove.size; i++) {
            int bid = toRemove.get(i);
            bridgeWarmupMap.remove(bid, 0f);
            bridgePosMap.remove(bid);
        }

        // Recolectar datos de sombra de unidades (evita QuadTree NPE dentro de lambdas Draw.draw).
        final java.util.ArrayList<UnitShadowData> unitShadows = new java.util.ArrayList<>();
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
                UnitShadowData d = new UnitShadowData();
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


        // 5-Tier Shadow Pipeline
        for (int t = 0; t < ShadowLayerConfig.NUM_TIERS; t++) {
            final int tier = t;
            final float drawZ = Layers.getZ(tier);

            Draw.draw(drawZ, () -> {
                tierFbo[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                Draw.color(0.04f, 0.03f, 0.08f);

                // Dibujar proyectores de sombras pertenecientes a este Tier
                for (int cx = chX1; cx <= chX2; cx++) {
                    for (int cy = chY1; cy <= chY2; cy++) {
                        if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                        ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                        if (!chunk.valid) {
                            ChunkCache.requestRebuildAsync(cx, cy);
                            if (chunk.tierCasters[0] == null) {
                                ChunkCache.rebuildChunkSync(cx, cy);
                                chunk = ChunkCache.chunks[cx][cy];
                            }
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

                            if (e.isProp) {
                                float propH = e.region.height * Draw.scl;
                                float propLen = propH * (shadowScale / 80f) * 1.35f * propShadowScale;
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
                                    Draw.color(0.02f, 0.015f, 0.04f, contactShadow * 0.70f);
                                    float rectSize = e.rawSize;
                                    Fill.rect(e.cx, e.cy, rectSize, rectSize);
                                    Draw.color(0.04f, 0.03f, 0.08f, 1f);
                                }
                                AnyBlocksShadows.draw(e.cx, e.cy, e.size, fLen, cosA, sinA);
                            }
                        }
                    }
                }

                // Dibujar sombras de unidades pre-recolectadas
                if (!unitShadows.isEmpty()) {
                    for (UnitShadowData ud : unitShadows) {
                        if (ud.tier != tier) continue;
                        Draw.color(0.04f, 0.03f, 0.08f, ud.alpha);
                        Draw.rect(ud.region, ud.x, ud.y, ud.w, ud.h, ud.rotation - 90);
                    }
                }

                // Sombras de enlaces de puente dibujadas en Tier 0 (Z=29.0f) para recibir desenfoque completo
                // y renderizarse por debajo de la viga del puente (Z=70f)
                if (tier == ShadowLayerConfig.TIER_SMALL && !bridgeLinks.isEmpty()) {
                    float bridgeFLen = shadowScale * 0.025f;
                    for (float[] lk : bridgeLinks) {
                        drawBridgeLinkShadow(lk[0], lk[1], lk[2], lk[3], bridgeFLen, cosA, sinA, lk[4]);
                    }
                }
                // Borrar la huella del propio bloque en el Tier actual para que no se sombree a sí mismo
                Draw.flush();
                Draw.blend(arc.graphics.Blending.disabled);
                Draw.color(0f, 0f, 0f, 0f);
                eraseTierFootprints(chX1, chY1, chX2, chY2, tier, screenX1, screenY1, screenX2, screenY2);

                // Enmascaramiento para Tier 4 (Montañas): borrar casillas de paredes rocosas para evitar autosombra
                if (tier == ShadowLayerConfig.TIER_ENV) {
                    eraseWallTiles(tx1, ty1, tx2, ty2, ts);
                }

                // Borrar casillas de suelo luminoso, líquido o espacio
                eraseFloorTiles(tx1, ty1, tx2, ty2, ts);

                Draw.flush();
                Draw.blend(arc.graphics.Blending.normal);
                tierFbo[tier].end();

                // Pase B: Desenfoque horizontal
                tierFbo2[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                applyShaderPass(1f, 0f, fSunElev, tierReg[tier], camX, camY, camW, camH);
                Draw.flush();
                tierFbo2[tier].end();

                // Pase C: Desenfoque vertical
                tierFbo3[tier].begin();
                Gl.clearColor(0f, 0f, 0f, 0f);
                Gl.clear(GL20.GL_COLOR_BUFFER_BIT);
                applyShaderPass(0f, 1f, fSunElev, tierReg2[tier], camX, camY, camW, camH);

                // Re-borrar huellas de estructuras y montañas tras el desenfoque para mantener techos 100% limpios
                Draw.flush();
                Draw.blend(arc.graphics.Blending.disabled);
                Draw.color(0f, 0f, 0f, 0f);
                eraseTierFootprints(chX1, chY1, chX2, chY2, tier, screenX1, screenY1, screenX2, screenY2);
                if (tier == ShadowLayerConfig.TIER_ENV) {
                    eraseWallTiles(tx1, ty1, tx2, ty2, ts);
                }
                Draw.flush();
                Draw.blend(arc.graphics.Blending.normal);

                tierFbo3[tier].end();

                // Dibujar textura final desenfocada de sombra para este Tier
                if (enabled && tierReg3[tier] != null && tierReg3[tier].texture != null) {
                    Draw.color(Color.white, alpha);
                    Draw.rect(tierReg3[tier], camX, camY, camW, camH);
                    Draw.color();
                }
            });
        }


    }

    private static void eraseTierFootprints(int chX1, int chY1, int chX2, int chY2, int tier, float sX1, float sY1, float sX2, float sY2) {
        for (int cx = chX1; cx <= chX2; cx++) {
            for (int cy = chY1; cy <= chY2; cy++) {
                if (cx < 0 || cx >= ChunkCache.mapW || cy < 0 || cy >= ChunkCache.mapH) continue;
                ChunkCache.CasterChunk chunk = ChunkCache.chunks[cx][cy];
                if (!chunk.valid) continue;
                for (int t = tier; t < ShadowLayerConfig.NUM_TIERS; t++) {
                    Seq<ChunkCache.CasterEntry> list = chunk.tierCasters[t];
                    for (int i = 0; i < list.size; i++) {
                        ChunkCache.CasterEntry e = list.get(i);
                        if (e.mod == 0f || e.isProp) continue;
                        float whs = e.size * 0.5f + 1f;
                        if (e.cx + whs < sX1 || e.cx - whs > sX2 || e.cy + whs < sY1 || e.cy - whs > sY2) continue;
                        // Borrar la huella del bloque: ligeramente menor a rawSize para evitar cortar la sombra del vecino
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
    private static void eraseWallTiles(int tx1, int ty1, int tx2, int ty2, float ts) {
        for (int ex = tx1; ex <= tx2; ex++) {
            for (int ey = ty1; ey <= ty2; ey++) {
                Tile t = Vars.world.tile(ex, ey);
                if (t != null && t.build == null && t.block().solid && ShadowLayerConfig.isMountainOrWall(t.block())) {
                    // Borrar huella exacta de baldosa de montaña para evitar huecos entre casillas adyacentes
                    Fill.rect(ex * ts, ey * ts, ts, ts);
                }
            }
        }
    }

    private static void eraseFloorTiles(int tx1, int ty1, int tx2, int ty2, float ts) {
        for (int ex = tx1; ex <= tx2; ex++) {
            for (int ey = ty1; ey <= ty2; ey++) {
                if (shouldEraseShadow(Vars.world.tile(ex, ey))) {
                    Fill.rect(ex * ts, ey * ts, ts + 0.1f, ts + 0.1f);
                }
            }
        }
    }

    private static float qualityScale() {
        switch (graphicsQuality) {
            case 0:  return 0.50f;
            case 1:  return 0.75f;
            default: return 1.00f;
        }
    }

    private static void applyShaderPass(float dx, float dy, float sunElev, TextureRegion src, float cx, float cy, float cw, float ch) {
        if (shadowShader == null) shadowShader = new ShadowShader();
        float currentPpu = (float)Core.graphics.getWidth() / Core.camera.width;
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
        for (int t = 0; t < ShadowLayerConfig.NUM_TIERS; t++) {
            for (FrameBuffer b : new FrameBuffer[]{tierFbo[t], tierFbo2[t], tierFbo3[t]}) {
                if (b != null) try { b.dispose(); } catch (Exception ignored) {}
            }
            tierFbo[t] = tierFbo2[t] = tierFbo3[t] = null;
        }
    }

    private static boolean isSolidAt(int x, int y) {
        Tile t = Vars.world.tile(x, y);
        if (t == null) return false;
        if (t.build != null) return t.build.block.solid;
        return t.block().solid;
    }

    private static boolean isBuriedTile(int x, int y) {
        return isSolidAt(x+1, y) && isSolidAt(x-1, y) && isSolidAt(x, y+1) && isSolidAt(x, y-1);
    }

    private static boolean isBuriedMountain(int x, int y) {
        return isSolidAt(x+1, y)   && isSolidAt(x-1, y)   && isSolidAt(x, y+1)   && isSolidAt(x, y-1)
            && isSolidAt(x+1, y+1) && isSolidAt(x-1, y+1) && isSolidAt(x+1, y-1) && isSolidAt(x-1, y-1);
    }

    private static boolean isBuildingBuried(mindustry.gen.Building build) {
        if (build == null || build.block == null) return false;
        int sz = build.block.size;
        int tx = build.tileX();
        int ty = build.tileY();
        int offset = (sz - 1) / 2;
        int minX = tx - offset;
        int maxX = minX + sz - 1;
        int minY = ty - offset;
        int maxY = minY + sz - 1;

        for (int x = minX; x <= maxX; x++) {
            if (!isSolidAt(x, maxY + 1) || !isSolidAt(x, minY - 1)) return false;
        }
        for (int y = minY; y <= maxY; y++) {
            if (!isSolidAt(maxX + 1, y) || !isSolidAt(minX - 1, y)) return false;
        }
        return true;
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

    private static boolean shouldEraseShadow(Tile t) {
        if (t == null) return true;
        if (t.build != null && t.block().solid) return false;
        Floor fl = t.floor();
        if (fl == null) return false;
        String n = fl.name.toLowerCase();
        if (fl == mindustry.content.Blocks.space || fl == mindustry.content.Blocks.empty
                || n.contains("space") || n.contains("void")
                || n.contains("empty") || n.contains("dark-panel")) return true;
        return n.contains("slag") || n.contains("lava") || n.contains("magma") || n.contains("hot") || n.contains("cryo");
    }

    private static class UnitShadowData {
        TextureRegion region;
        int tier;
        float x, y, w, h, rotation, alpha;
    }

    // 16x16 Chunk-Based Static Caster Cache (Categorized by 5 Tiers with Multithreaded Background Rebuilding)
    public static class ChunkCache {
        public static final int CHUNK_SIZE = 16;
        public static volatile CasterChunk[][] chunks;
        public static int mapW = 0, mapH = 0;
        public static boolean initialized = false;

        private static java.util.concurrent.ExecutorService threadPool;
        private static final java.util.concurrent.ConcurrentHashMap<Long, Boolean> pendingChunks = new java.util.concurrent.ConcurrentHashMap<>();

        public static class CasterChunk {
            public final Seq<CasterEntry>[] tierCasters = new Seq[ShadowLayerConfig.NUM_TIERS];
            public boolean valid = false;

            public CasterChunk() {
                for (int i = 0; i < ShadowLayerConfig.NUM_TIERS; i++) {
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
            int threads = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8));
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

                    Floor fl = tile.floor();
                    if (isLuminousFloor(fl)) continue;

                    boolean isBuild = tile.build != null && tile.isCenter();
                    boolean isProp  = !oldShadowsEnabled && !isBuild 
                                      && (tile.block() instanceof mindustry.world.blocks.environment.Prop 
                                          || tile.block() instanceof mindustry.world.blocks.environment.TreeBlock)
                                      && !(tile.block() instanceof mindustry.world.blocks.environment.StaticWall);
                    boolean isMtn   = !isBuild && !isProp && tile.block().solid && ShadowLayerConfig.isMountainOrWall(tile.block());
                    // Paredes/montañas enterradas por vecinos sólidos no tienen borde expuesto; omitir
                    boolean isWall  = !isBuild && !isProp && tile.block().solid
                                      && (isMtn ? !isBuriedMountain(x, y) : !isBuriedTile(x, y));
                    if (!isBuild && !isWall && !isProp) continue;
                    if (isBuild && tile.build.block instanceof mindustry.world.blocks.power.LightBlock) continue;
                    // Omitir edificios completamente rodeados por estructuras sólidas (sin perímetro expuesto)
                    if (isBuild && (tile.build.block.size > 1 ? isBuildingBuried(tile.build) : isBuriedTile(x, y))) continue;

                    Block blk = isBuild ? tile.build.block : tile.block();
                    int tier = ShadowLayerConfig.getTier(blk);

                    CasterEntry e = new CasterEntry();
                    e.x = x;
                    e.y = y;
                    e.cx = isBuild ? tile.build.x : tile.worldx();
                    e.cy = isBuild ? tile.build.y : tile.worldy();
                    e.rawSize = isBuild ? tile.build.block.size * Vars.tilesize : Vars.tilesize;
                    // Baldosas de montaña/pared usan traslape extra (+2.0f) para eliminar costuras de rasterizado subpíxel.
                    e.size = e.rawSize + (isMtn ? 2.0f : 0.4f);
                    e.elev = isBuild ? getElev(tile.build.block, 1f) : getElev(tile.block(), 1.6f);
                    e.mod = AnyBlocksShadows.getModifier(blk);
                    e.isProp = isProp;
                    e.tier = tier;

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
            }
        }
    }
}
