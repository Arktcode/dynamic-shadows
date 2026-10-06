package dynamicShadows;

import arc.Core;
import arc.graphics.gl.Shader;
import mindustry.Vars;

public class ShadowShader extends Shader {
    public float radius = 3.5f, blurDirX = 1f, blurDirY = 0f, edgeNoise = 0.38f;
    public float shadowTint = 0.60f, contactShadow = 0.45f, sunElevation = 0.5f;
    public float camW = 1f, camH = 1f;

    private int uRadius = -1, uBlurDir = -1, uEdgeNoise = -1, uShadowTint = -1;
    private int uContactShadow = -1, uSunElevation = -1, uTime = -1, uResolution = -1;
    private int uCameraPos = -1, uCamSize = -1;

    public ShadowShader() {
        super(Vars.tree.get("shaders/shadow.vert"), Vars.tree.get("shaders/shadow.frag"));
        cacheUniforms();
    }

    private void cacheUniforms() {
        uRadius = getUniformLocation("u_radius");
        uBlurDir = getUniformLocation("u_blurDir");
        uEdgeNoise = getUniformLocation("u_edgeNoise");
        uShadowTint = getUniformLocation("u_shadowTint");
        uContactShadow = getUniformLocation("u_contactShadow");
        uSunElevation = getUniformLocation("u_sunElevation");
        uTime = getUniformLocation("u_time");
        uResolution = getUniformLocation("u_resolution");
        uCameraPos = getUniformLocation("u_cameraPos");
        uCamSize = getUniformLocation("u_camSize");
    }

    @Override
    public void apply() {
        if (uRadius < 0) cacheUniforms();

        setUniformf(uRadius, radius);
        setUniformf(uBlurDir, blurDirX, blurDirY);
        setUniformf(uEdgeNoise, edgeNoise);
        setUniformf(uShadowTint, shadowTint);
        setUniformf(uContactShadow, contactShadow);
        setUniformf(uSunElevation, sunElevation);
        setUniformf(uTime, arc.util.Time.time * 0.05f);
        setUniformf(uResolution, Core.graphics.getWidth(), Core.graphics.getHeight());
        setUniformf(uCameraPos, Core.camera.position.x, Core.camera.position.y);
        setUniformf(uCamSize, camW, camH);
    }
}
