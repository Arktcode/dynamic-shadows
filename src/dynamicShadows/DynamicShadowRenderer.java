package dynamicShadows;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mathf;
import arc.struct.*;
import mindustry.Vars;
import mindustry.world.*;

public class DynamicShadowRenderer {
    public static float BASE_SHADOW_ANGLE = 210f, SHADOW_LENGTH = 10f, SHADOW_ALPHA = 0.38f, weatherMult = 1f;
    public static boolean enabled = true, dayNightCycle = true;
    public static float darkFadeThreshold = 0.35f, darkFadeStrength = 0.80f, blurRadius = 3.5f, edgeNoise = 0.38f;
    public static float shadowTint = 0.60f, contactShadow = 0.45f, godraysStrength = 0.75f;

    private static FrameBuffer fbo, fbo2, fbo3;
    private static TextureRegion fboReg, fboReg2, fboReg3;
    private static ShadowShader shadowShader;
    private static final ObjectFloatMap<Block> elevCache = new ObjectFloatMap<>(64);
    private static TextureRegion emptyRegion;

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
                darkness = Mathf.clamp(np * 0.80f, 0f, 0.70f);
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

        //Dark fade (shadows dissolve)
        int lightCount = LightMapRenderer.getCachedLights().size;
        float lightPresence = Mathf.clamp(lightCount / 6f, 0f, 1f);
        float darkFade = 1f;
        if (darkness > darkFadeThreshold) {
            float depth = Mathf.clamp((darkness - darkFadeThreshold) / (1f - darkFadeThreshold), 0f, 1f);
            darkFade = 1f - depth * (1f - lightPresence) * darkFadeStrength;
        }

        final float shadowLen = SHADOW_LENGTH * Vars.tilesize * (1f + darkness * 1.2f);
        final float alpha     = SHADOW_ALPHA * Mathf.clamp(weatherMult) * (1f - darkness * 0.5f) * darkFade;

        // Hide engine unit shadows
        Vars.content.units().each(type -> {
            AnyBlocksShadows.getOriginalShadow(type);
            if (type.shadowRegion != emptyRegion) type.shadowRegion = emptyRegion;
        });

        // Viewport tile range
        final float camX = Core.camera.position.x, camY = Core.camera.position.y;
        final float camW = Core.camera.width,       camH = Core.camera.height;
        float maxST   = SHADOW_LENGTH * (1f + darkness * 1.2f) * 4f + 4f;
        float margin  = (maxST + 8f) * Vars.tilesize;
        int wMax = Vars.world.width()-1, hMax = Vars.world.height()-1;
        final int tx1 = Mathf.clamp((int)((camX-camW*.5f-margin)/Vars.tilesize),0,wMax);
        final int ty1 = Mathf.clamp((int)((camY-camH*.5f-margin)/Vars.tilesize),0,hMax);
        final int tx2 = Mathf.clamp((int)((camX+camW*.5f+margin)/Vars.tilesize),0,wMax);
        final int ty2 = Mathf.clamp((int)((camY+camH*.5f+margin)/Vars.tilesize),0,hMax);

        // Pre-filter lights to avoid O(Tiles * AllLights) complexity
        final Seq<LightEntry> activeLights = new Seq<>();
        for(LightEntry le : LightMapRenderer.getCachedLights()){
            if(le.radius > 30f && Mathf.dst(le.x, le.y, camX, camY) < margin + camW) activeLights.add(le);
        }

        final float fDark = darkness, fSunElev = rawSun;
        LightMapRenderer.maybeRebuildCache(tx1, ty1, tx2, ty2);

        Draw.draw(18f, () -> {
            int gw = Core.graphics.getWidth(), gh = Core.graphics.getHeight();
            if (fbo == null || fbo.getWidth()!=gw || fbo.getHeight()!=gh
                    || fbo.getTexture()==null || fbo.getTexture().getTextureObjectHandle()==0) {
                disposeFBOs();
                try {
                    fbo  = new FrameBuffer(gw,gh); fboReg  = flipped(fbo);
                    fbo2 = new FrameBuffer(gw,gh); fboReg2 = flipped(fbo2);
                    fbo3 = new FrameBuffer(gw,gh); fboReg3 = flipped(fbo3);
                } catch (Exception e) { return; }
            }

            Draw.flush();
            fbo.begin(Color.clear);
            Draw.color(0.04f, 0.03f, 0.08f, 1f);
            final float shadowScale = SHADOW_LENGTH * Vars.tilesize * (1f + fDark * 1.2f);

            for (int x=tx1; x<=tx2; x++) {
                for (int y=ty1; y<=ty2; y++) {
                    Tile tile = Vars.world.tiles.getn(x, y);
                    if (tile==null) continue;
                    boolean isBuild = tile.build!=null && tile.isCenter();
                    boolean isWall  = !isBuild && tile.block().solid && !isBuriedWall(x,y);
                    if (!isBuild && !isWall) continue;
                    if (isBuild && tile.block().size < 2 && tile.build.block.emitLight && tile.build.block.lightRadius>30f) continue;

                    float cx = isBuild ? tile.build.x : tile.worldx(), cy = isBuild ? tile.build.y : tile.worldy();
                    float size = (isBuild ? tile.build.block.size*Vars.tilesize : Vars.tilesize)+.5f;
                    float elev = isBuild ? getElev(tile.build.block,1f) : getElev(tile.block(),1.6f);
                    float mod  = AnyBlocksShadows.getModifier(isBuild ? tile.build.block : tile.block());

                    float maxLI = 0f;
                    for (int i=0; i<activeLights.size; i++) {
                        LightEntry lp = activeLights.get(i);
                        if (!lp.isStrongLight) continue;
                        float dst = Mathf.dst(cx,cy,lp.x,lp.y);
                        if (dst < lp.radius) {
                            float inf = 1f - (dst/lp.radius);
                            if (inf > maxLI) maxLI = inf;
                        }
                    }

                    float fLen = shadowScale * elev * mod * (1f - maxLI);
                    if (fLen < 0.2f) continue;

                    if (contactShadow > 0f) {
                        float diskR = size * 0.52f * (0.6f + elev*0.4f);
                        Draw.color(0.02f, 0.015f, 0.04f, contactShadow * 0.70f * (1f-maxLI));
                        Fill.circle(cx, cy, diskR);
                        Draw.color(0.04f, 0.03f, 0.08f, 1f);
                    }
                    AnyBlocksShadows.draw(cx, cy, size, fLen, cosA, sinA);
                }
            }

            LightMapRenderer.eraseShadowsInLitAreas(fDark);
            Draw.flush();
            Draw.blend(arc.graphics.Blending.disabled);
            Draw.color(Color.clear);
            eraseSpace(tx1,ty1,tx2,ty2);
            Draw.flush();
            Draw.blend(arc.graphics.Blending.normal);
            fbo.end();

            fbo2.begin(Color.clear);
            applyShader(1f,0f, fSunElev, fboReg,  camX,camY,camW,camH);
            Draw.flush();
            fbo2.end();

            fbo3.begin(Color.clear);
            applyShader(0f,1f, fSunElev, fboReg2, camX,camY,camW,camH);

            Draw.color(0.04f, 0.03f, 0.08f, 1f);
            mindustry.gen.Groups.unit.intersect(camX-margin,camY-margin,camW+margin*2f,camH+margin*2f, u -> {
                TextureRegion us = AnyBlocksShadows.getOriginalShadow(u.type);
                if (us!=null && us.found()) {
                    float fl = shadowLen * (1f + u.elevation*2.8f) * 0.025f;
                    Draw.rect(us, u.x+cosA*fl, u.y+sinA*fl, u.rotation-90);
                }
            });

            Draw.flush();
            Draw.blend(arc.graphics.Blending.disabled);
            Draw.color(Color.clear);
            eraseSpace(tx1,ty1,tx2,ty2);
            Draw.flush();
            Draw.blend(arc.graphics.Blending.normal);
            fbo3.end();

            if (fboReg3!=null && fboReg3.texture!=null) {
                Draw.color(Color.white, alpha);
                Draw.rect(fboReg3, camX,camY,camW,camH);
                Draw.color();
            }
        });
    }

    // Helpers

    private static void applyShader(float dx, float dy, float sunElev, TextureRegion src, float cx, float cy, float cw, float ch) {
        if (shadowShader==null) shadowShader = new ShadowShader();
        shadowShader.radius = blurRadius;
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

    private static void eraseSpace(int tx1,int ty1,int tx2,int ty2) {
        for (int x=tx1; x<=tx2; x++) for (int y=ty1; y<=ty2; y++) {
            Tile t = Vars.world.tile(x,y);
            boolean sp = t==null;
            if (!sp && t.floor()!=null) {
                String fn = t.floor().name;
                sp = t.floor()==mindustry.content.Blocks.space || t.floor()==mindustry.content.Blocks.empty
                        || fn.contains("space")||fn.contains("void")||fn.contains("empty")||fn.contains("dark-panel");
            }
            if (sp && (t==null||(t.build==null&&!t.block().solid)))
                Fill.rect(x*Vars.tilesize, y*Vars.tilesize, Vars.tilesize, Vars.tilesize);
        }
    }

    private static TextureRegion flipped(FrameBuffer fb) {
        TextureRegion r = new TextureRegion(fb.getTexture()); r.flip(false,true); return r;
    }

    private static void disposeFBOs() {
        for (FrameBuffer b : new FrameBuffer[]{fbo,fbo2,fbo3})
            if (b!=null) try { b.dispose(); } catch (Exception ignored) {}
        fbo=fbo2=fbo3=null;
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
}
