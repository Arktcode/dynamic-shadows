package dynamicShadows;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Interp;
import arc.math.Mathf;
import arc.scene.actions.Actions;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.mod.Mods;
import mindustry.ui.Styles;

public class DUI {

    private static final String VERSION = "v1.6.160.5";

    private static Table cont;
    private static Table borderFrame;
    private static Table overlayCenter;
    private static Table overlayChangelog;
    private static arc.scene.Element[] changeItemLabels;
    private static CordSwitch cordSection;

    private static boolean shown = false;

    public static void tryShow() {
        Log.info("[DUI] tryShow() llamado. shown=" + shown + " headless=" + Vars.headless);
        if (shown || Vars.headless) return;
        shown = true;
        build();
        animate();
    }

    private static void build() {
        cont = new Table();
        cont.setFillParent(true);
        cont.setBackground(Styles.black);
        cont.touchable = Touchable.enabled;

        // Marco decorativo blanco (5px margen, 4px grosor) porque soy goloso grrrr
        borderFrame = new Table();
        borderFrame.setFillParent(true);
        borderFrame.touchable = Touchable.disabled;
        borderFrame.margin(5f);

        Color bCol = Color.white;
        Table topBar = new Table();
        topBar.setBackground(new TextureRegionDrawable(Core.atlas.find("white")));
        topBar.color.set(bCol);
        Table bottomBar = new Table();
        bottomBar.setBackground(new TextureRegionDrawable(Core.atlas.find("white")));
        bottomBar.color.set(bCol);
        Table leftBar = new Table();
        leftBar.setBackground(new TextureRegionDrawable(Core.atlas.find("white")));
        leftBar.color.set(bCol);
        Table rightBar = new Table();
        rightBar.setBackground(new TextureRegionDrawable(Core.atlas.find("white")));
        rightBar.color.set(bCol);
        borderFrame.add(topBar).growX().height(4f).colspan(3).row();
        borderFrame.add(leftBar).width(4f).growY();
        borderFrame.add().grow();
        borderFrame.add(rightBar).width(4f).growY().row();
        borderFrame.add(bottomBar).growX().height(4f).colspan(3);

        overlayCenter = new Table();
        overlayCenter.setFillParent(true);
        overlayCenter.touchable = Touchable.disabled;
        overlayCenter.center();

        Table inner = new Table();
        inner.center();

        // Logo del mod (escalado según si es móvil o PC)
        float logoSize = Vars.mobile ? 140f : 210f;
        TextureRegion icon = resolveIcon();
        if (icon != null) {
            Image img = new Image(icon);
            inner.add(img).size(logoSize).padBottom(10f).row();
        }

        Label title = new Label("[#fed17b]Dynamic Shadows", Styles.outlineLabel);
        inner.add(title).center().row();

        String byWord = Core.bundle.get("dui.by", "by");
        Label sub = new Label("[gray]" + VERSION + "   [white]" + byWord + "[gray] Arksource", Styles.outlineLabel);
        sub.setFontScale(0.90f);
        inner.add(sub).center().padTop(5f).row();

        overlayCenter.add(inner).center();

        overlayChangelog = new Table();
        overlayChangelog.setFillParent(true);
        overlayChangelog.touchable = Touchable.disabled;
        // setTransform desactivado para texto nítido
        overlayChangelog.top().left();

        Table clBox = new Table();
        clBox.top().left();
        String headerFmt = Core.bundle.get("dui.news.header", "— Novedades {0} —");
        String headerStr = headerFmt.contains("{0}") ? headerFmt.replace("{0}", VERSION) : headerFmt + " " + VERSION;
        Label clHeader = new Label("[accent]" + headerStr, Styles.outlineLabel);
        clHeader.setFontScale(0.90f);
        clBox.add(clHeader).left().padBottom(6f).row();

        String[] entries = {
                Core.bundle.get("dui.news.1", "[white]• [lightgray]Mejora de calidad."),
                Core.bundle.get("dui.news.2", "[white]• [lightgray]Culling direccional."),
                Core.bundle.get("dui.news.3", "[white]• [lightgray]Optimización grafica."),
                Core.bundle.get("dui.news.4", "[white]• [lightgray]First Stable Version.")
        };

        java.util.ArrayList<arc.scene.Element> seq = new java.util.ArrayList<>();

        float newsScale = Vars.mobile ? 0.70f : 0.78f;
        for (String entry : entries) {
            Label lbl = new Label(entry, Styles.outlineLabel);
            lbl.setFontScale(newsScale);
            lbl.color.a = 0f;
            seq.add(lbl);
            clBox.add(lbl).left().padTop(3f).row();
        }

        Table clDivider = new Table();
        clDivider.setBackground(new TextureRegionDrawable(Core.atlas.find("white")));
        clDivider.color.set(Color.valueOf("fed17b"));
        clDivider.color.a = 0f;
        seq.add(clDivider);
        clBox.add(clDivider).growX().height(1f).padTop(12f).padBottom(8f).row();

        Label clCredTitle = new Label("[gray]" + Core.bundle.get("dui.credits.header", "Colaboradores & Créditos:"), Styles.outlineLabel);
        clCredTitle.setFontScale(0.80f);
        clCredTitle.color.a = 0f;
        seq.add(clCredTitle);
        clBox.add(clCredTitle).left().padBottom(4f).row();

        // Fila de colaboradores
        Table clCollabRow = new Table();

        Label cl1 = new Label("[#a2e798]owo [gray](Sentinel)", Styles.outlineLabel);
        cl1.setFontScale(0.85f);
        clCollabRow.add(cl1).padRight(8f);

        Label clDot1 = new Label("[gray]·", Styles.outlineLabel);
        clDot1.setFontScale(0.85f);
        clCollabRow.add(clDot1).padRight(8f);

        Label cl2 = new Label("[#f37272]S_m_i_t_e", Styles.outlineLabel);
        cl2.setFontScale(0.85f);
        clCollabRow.add(cl2).padRight(8f);

        Label clDot2 = new Label("[gray]·", Styles.outlineLabel);
        clDot2.setFontScale(0.85f);
        clCollabRow.add(clDot2).padRight(8f);

        Label cl3 = new Label("[#ffffff]128_OTEMLn", Styles.outlineLabel);
        cl3.setFontScale(0.85f);
        clCollabRow.add(cl3);

        clCollabRow.color.a = 0f;
        seq.add(clCollabRow);
        clBox.add(clCollabRow).left().padBottom(3f).row();

        Label clNote = new Label("[gray]" + Core.bundle.get("dui.credits.note", "por ideas, desarrollo y testeo."), Styles.outlineLabel);
        clNote.setFontScale(0.72f);
        clNote.color.a = 0f;
        seq.add(clNote);
        clBox.add(clNote).left().row();

        changeItemLabels = seq.toArray(new arc.scene.Element[0]);
        float clPad = Vars.mobile ? 14f : 30f;
        overlayChangelog.add(clBox).expandX().top().left().pad(clPad);

        cordSection = new CordSwitch();

        cont.addChild(borderFrame);
        cont.addChild(overlayCenter);
        cont.addChild(overlayChangelog);
        Core.scene.add(cont);
        Core.scene.add(cordSection);
        cont.toFront();
        cordSection.toFront();

        Log.info("[DUI] Splash mejorado y centrado construido correctamente.");
    }

    private static void animate() {
        if (cont == null) return;

        // Marco: fadeIn suave
        borderFrame.color.a = 0f;
        borderFrame.actions(Actions.sequence(Actions.alpha(0f), Actions.fadeIn(0.60f, Interp.pow3Out)));

        // Centro: fadeIn + ascenso suave desde abajo
        overlayCenter.color.a = 0f;
        overlayCenter.actions(Actions.sequence(
                Actions.alpha(0f), Actions.delay(0.20f),
                Actions.parallel(
                        Actions.fadeIn(0.90f, Interp.pow3Out),
                        Actions.sequence(Actions.moveBy(0f, -14f), Actions.moveBy(0f, 14f, 0.70f, Interp.pow3Out))
                )
        ));

        overlayChangelog.color.a = 0f;
        overlayChangelog.actions(Actions.sequence(
                Actions.alpha(0f), Actions.delay(0.50f),
                Actions.fadeIn(0.60f, Interp.pow3Out)
        ));

        float baseDelay = 0.80f;
        float interval = 0.35f;
        for (int i = 0; i < changeItemLabels.length; i++) {
            arc.scene.Element elem = changeItemLabels[i];
            if (elem == null) continue;
            elem.color.a = 0f;

            final float finalA = (elem instanceof Table && ((Table)elem).getChildren().size == 0) ? 0.35f : 1f;
            elem.actions(Actions.sequence(
                    Actions.alpha(0f),
                    Actions.delay(baseDelay + i * interval),
                    Actions.alpha(finalA, 0.35f, Interp.pow3Out)
            ));
        }

        // Cuerda de bombilla: aparece al terminar de cargar todo el texto (novedades + créditos)
        float cordDelay = baseDelay + changeItemLabels.length * interval + 0.20f;
        if (cordSection != null) cordSection.dropIn(cordDelay);

        cont.actions(Actions.sequence(
                Actions.delay(22.75f),
                Actions.run(() -> {
                    if (cordSection != null) {
                        cordSection.actions(Actions.fadeOut(0.22f, Interp.pow2In));
                    }
                }),
                Actions.delay(0.25f),
                Actions.fadeOut(0.50f, Interp.pow2In),
                Actions.run(DUI::cleanup)
        ));
    }

    private static void skip() {
        if (cont == null) return;
        cont.clearActions();
        if (borderFrame != null) borderFrame.clearActions();
        if (overlayCenter != null) overlayCenter.clearActions();
        if (overlayChangelog != null) overlayChangelog.clearActions();
        if (cordSection != null) cordSection.clearActions();

        if (cordSection != null) {
            cordSection.actions(Actions.fadeOut(0.20f, Interp.pow2In));
        }

        cont.actions(Actions.sequence(
                Actions.delay(0.22f),
                Actions.fadeOut(0.48f, Interp.pow2In),
                Actions.run(DUI::cleanup)
        ));
    }

    private static void cleanup() {
        if (borderFrame != null) { borderFrame.remove(); borderFrame = null; }
        if (overlayCenter != null) { overlayCenter.remove(); overlayCenter = null; }
        if (overlayChangelog != null) { overlayChangelog.remove(); overlayChangelog = null; }
        if (cordSection != null) { cordSection.remove(); cordSection = null; }
        if (cont != null) { cont.remove(); cont = null; }
        Log.info("[DUI] Pantalla splash finalizada.");
    }

    private static TextureRegion resolveIcon() {
        try {
            Mods.LoadedMod mod = Vars.mods.getMod(Main.class);
            if (mod != null && mod.iconTexture != null) return new TextureRegion(mod.iconTexture);
        } catch (Exception e) {
            Log.err("[DUI] Error al resolver el icono: " + e.getMessage());
        }
        try {
            TextureRegion r = Core.atlas.find("dynamic-shadows-icon");
            if (r != null && r.found()) return r;
        } catch (Exception ignored) {}
        return null;
    }

    private static class CordSwitch extends Table {
        private float pullX = 0f;
        private float pullY = 0f;
        private float velX = 0f;
        private float velY = 0f;
        private boolean dragging = false;
        private boolean triggered = false;
        private boolean dropped = false;

        private final Label pullHint;

        private float getRestLen() { return Vars.mobile ? 270f : 200f; }
        private float getThreshold() { return Vars.mobile ? 140f : 130f; }
        private static final float K = 0.16f;
        private static final float DAMP = 0.74f;

        private float anchorX = -1f;

        private float hintAlpha = 0f;

        private int activePointer = -1;

        public CordSwitch() {
            setFillParent(true);
            touchable = Touchable.enabled;
            color.a = 0f;

            pullHint = new Label(Core.bundle.get("dui.pull", "PULL"), Styles.outlineLabel);
            pullHint.setFontScale(Vars.mobile ? 0.72f : 0.78f);
            pullHint.setColor(Color.valueOf("bbbbbb"));
            pullHint.color.a = 0f;
            addChild(pullHint);

            addListener(new InputListener() {
                private float sx, sy;

                @Override
                public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode btn) {
                    if (!dropped || activePointer != -1) return false;

                    float W = Core.graphics.getWidth();
                    float H = Core.graphics.getHeight();
                    float ax = (anchorX > 0) ? anchorX : W - 65f;
                    float knobX = ax + pullX;
                    float knobY = H - 9f - (getRestLen() + pullY);

                    float dist = Mathf.len(e.stageX - knobX, e.stageY - knobY);
                    float maxGrab = Vars.mobile ? 90f : 60f;

                    if (dist > maxGrab) return false;

                    dragging = true;
                    activePointer = pointer;
                    sx = x;
                    sy = y;
                    return true;
                }

                @Override
                public void touchDragged(InputEvent e, float x, float y, int pointer) {
                    if (!dragging || triggered || !dropped || pointer != activePointer) return;
                    pullX = Mathf.clamp(x - sx, -160f, 160f);
                    pullY = Mathf.clamp(sy - y, -160f, 150f);

                    if (pullY >= getThreshold() && !triggered) {
                        triggered = true;
                        try { Vars.tree.loadSound("click").play(); } catch (Exception ignored) {}
                        skip();
                    }
                }

                @Override
                public void touchUp(InputEvent e, float x, float y, int pointer, KeyCode btn) {
                    if (pointer == activePointer) {
                        dragging = false;
                        activePointer = -1;
                    }
                }
            });
        }

        public void dropIn(float delay) {
            color.a = 0f;
            float W = Core.graphics.getWidth();
            anchorX = Mathf.random(120f, Math.max(130f, W - 120f));

            actions(Actions.sequence(
                    Actions.delay(delay),
                    Actions.run(() -> {
                        dropped = true;
                        pullY = -160f; pullX = 12f;
                        velY = 22f; velX = -3f;
                    }),
                    Actions.fadeIn(0.45f, Interp.pow3Out)
            ));
        }

        @Override
        public void act(float delta) {
            super.act(delta);

            if (!dragging && (Math.abs(pullX) > 0.1f || Math.abs(pullY) > 0.1f)) {
                velX += -pullX * K; velX *= DAMP; pullX += velX;
                velY += -pullY * K; velY *= DAMP; pullY += velY;
                if (Math.abs(pullX) < 0.2f && Math.abs(velX) < 0.2f) { pullX = 0f; velX = 0f; }
                if (Math.abs(pullY) < 0.2f && Math.abs(velY) < 0.2f) { pullY = 0f; velY = 0f; }
            }

            float W = Core.graphics.getWidth();
            float H = Core.graphics.getHeight();
            float sx = (anchorX > 0) ? anchorX : W - 65f;
            float bx = sx + pullX;
            float by = H - 9f - (getRestLen() + pullY);

            float mx = Core.input.mouseX();
            float my = H - Core.input.mouseY();
            float hoverDist = Vars.mobile ? 45f : 30f;

            boolean hovering = dropped && Mathf.len(mx - bx, my - by) < hoverDist;

            float targetAlpha = (hovering || dragging || !dropped) ? 0f : color.a * 0.60f;
            hintAlpha = Mathf.lerp(hintAlpha, targetAlpha, 0.12f);

            if (pullHint != null) {
                float hintW = pullHint.getPrefWidth();
                float hintOffset = Vars.mobile ? 48f : 38f;
                pullHint.setPosition(bx - hintW / 2f, by - hintOffset);
                pullHint.color.a = hintAlpha;
            }
        }

        @Override
        public void draw() {
            if (color.a <= 0.01f) return;
            super.draw();

            float W = Core.graphics.getWidth();
            float H = Core.graphics.getHeight();

            float sx = (anchorX > 0) ? anchorX : W - 65f;
            float sy = H - 9f;

            float ex = sx + pullX;
            float restL = getRestLen();
            float curLen = restL + pullY;
            float ey = sy - curLen;

            float alpha = color.a;

            float midX = (sx + ex) / 2f;
            float midY = (sy + ey) / 2f;

            float dist = Mathf.len(ex - sx, ey - sy);
            float slack = Math.max(0f, restL - dist);

            float sagY = (slack * 0.85f) + Math.max(0f, -pullY * 0.90f);
            float cy = midY - sagY;

            Draw.color(Color.white, alpha);
            int dotCount = Math.max(10, (int)(restL / 10f));
            float dotScale = Vars.mobile ? 1.45f : 1.0f;
            for (int i = 0; i <= dotCount; i++) {
                float t = (float) i / dotCount;
                float invT = 1f - t;
                float px = invT * invT * sx + 2f * invT * t * midX + t * t * ex;
                float py = invT * invT * sy + 2f * invT * t * cy + t * t * ey;

                float r = dotScale * ((i == 0) ? 2.5f : (i == dotCount ? 3.0f : 3.5f));
                Fill.circle(px, py, r);
            }

            float pullDist = Mathf.len(pullX, Math.max(0f, pullY));
            float glowT = Mathf.clamp(pullDist / getThreshold(), 0f, 1f);

            float knobR = Vars.mobile ? 28f : 20f;
            float innerR = Vars.mobile ? 18f : 13f;
            float centerR = Vars.mobile ? 7f : 5f;

            if (glowT > 0.05f) {
                Draw.color(Color.white, glowT * 0.20f * alpha);
                Fill.circle(ex, ey, (knobR * 2.1f) + glowT * 14f);
                Draw.color(Color.white, glowT * 0.30f * alpha);
                Fill.circle(ex, ey, knobR * 1.5f);
            }

            Draw.color(Color.white, alpha);
            Fill.circle(ex, ey, knobR);

            Draw.color(Color.valueOf("111111"), alpha);
            Fill.circle(ex, ey, innerR);

            Draw.color(Color.white, alpha * 0.90f);
            Fill.circle(ex, ey, centerR);

            Draw.reset();
        }
    }
}