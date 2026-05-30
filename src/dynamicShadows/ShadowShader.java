package dynamicShadows;

import arc.Core;
import arc.graphics.gl.Shader;
import mindustry.Vars;

public class ShadowShader extends Shader {
    public float radius = 3.5f, blurDirX = 1f, blurDirY = 0f, edgeNoise = 0.38f;
    public float shadowTint = 0.60f, contactShadow = 0.45f, sunElevation = 0.5f;
    public float camW = 1f, camH = 1f;

    public ShadowShader() {
        super(Vars.tree.get("shaders/shadow.vert"), Vars.tree.get("shaders/shadow.frag"));
    }

    @Override
    public void apply() {
        setUniformf("u_radius", radius);
        setUniformf("u_blurDir", blurDirX, blurDirY);
        setUniformf("u_edgeNoise", edgeNoise);
        setUniformf("u_shadowTint", shadowTint);
        setUniformf("u_contactShadow", contactShadow);
        setUniformf("u_sunElevation", sunElevation);
        setUniformf("u_time", arc.util.Time.time * 0.05f);
        setUniformf("u_resolution", Core.graphics.getWidth(), Core.graphics.getHeight());
        setUniformf("u_cameraPos", Core.camera.position.x, Core.camera.position.y);
        setUniformf("u_camSize", camW, camH);
    }
}
