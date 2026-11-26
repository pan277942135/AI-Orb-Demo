package com.example.waterworld

import android.opengl.GLES30

/** Shared image-space mesh program. Lighting inputs belong to the scene configuration. */
internal class WaterWorldProgram {
    val id: Int
    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
        val fragment = try { compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT) }
            catch (failure: Throwable) { GLES30.glDeleteShader(vertex); throw failure }
        id = GLES30.glCreateProgram()
        try {
            GLES30.glAttachShader(id, vertex); GLES30.glAttachShader(id, fragment)
            GLES30.glLinkProgram(id)
            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(id) }
        } catch (failure: Throwable) { GLES30.glDeleteProgram(id); throw failure }
        finally { GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment) }
    }
    fun uniform(name: String): Int = GLES30.glGetUniformLocation(id, name).also {
        check(it >= 0) { "Missing scene uniform $name" }
    }
    fun release() = GLES30.glDeleteProgram(id)
    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source); GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error(log)
        }
        return shader
    }
    companion object {
        val VERTEX = """#version 300 es
            precision highp float;
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec2 aUv;
            uniform mat4 uModel;
            uniform vec2 uCanvas;
            out vec2 vUv;
            out vec3 vNormal;
            void main() {
                vec4 p=uModel*vec4(aPosition,1.0);
                gl_Position=vec4(p.x/uCanvas.x*2.0-1.0,1.0-p.y/uCanvas.y*2.0,p.z/2048.0,1.0);
                vNormal=normalize(transpose(inverse(mat3(uModel)))*aNormal);
                vUv=aUv;
            }
        """.trimIndent()
        val FRAGMENT = """#version 300 es
            precision highp float;
            in vec2 vUv;
            in vec3 vNormal;
            uniform sampler2D uTexture;
            uniform vec4 uTint;
            uniform vec3 uLightDirection;
            uniform vec3 uLightColor;
            uniform float uAmbient;
            uniform float uEmission;
            uniform float uUseTexture;
            uniform float uFresnel;
            uniform float uShellSide;
            out vec4 color;
            void main() {
                if(uShellSide!=0.0 && vNormal.z*uShellSide<0.0) discard;
                vec4 base=mix(vec4(1.0),texture(uTexture,vUv),uUseTexture)*uTint;
                if(base.a<0.001) discard;
                // Ribbon UV.v spans the original stroke width; soften its boundary
                // and concentrate emission around its center instead of a flat opaque band.
                float ribbonWeight=step(0.001,uEmission)*(1.0-uUseTexture);
                float across=abs(vUv.y*2.0-1.0);
                float ribbonAlpha=1.0-smoothstep(0.55,1.0,across);
                float ribbonCrest=exp(-18.0*across*across);
                base.a*=mix(1.0,ribbonAlpha,ribbonWeight);
                base.rgb*=mix(1.0,0.55+0.45*ribbonCrest,ribbonWeight);
                float diffuse=max(dot(normalize(vNormal),normalize(uLightDirection)),0.0);
                float edge=pow(1.0-abs(normalize(vNormal).z),3.0);
                base.a*=mix(1.0,0.025+0.975*edge,uFresnel);
                color=vec4(base.rgb*(vec3(uAmbient)+uLightColor*diffuse+vec3(uEmission)),base.a);
            }
        """.trimIndent()
    }
}
