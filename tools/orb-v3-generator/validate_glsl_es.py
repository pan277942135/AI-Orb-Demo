#!/usr/bin/env python3
"""Compile and link production shader pairs in a surfaceless GLES 3 context.

This validates the actual GLSL ES compiler and linker used by the current EGL stack.
Mesa may provide a software renderer here; this is shader validation only and is not
hardware GPU render evidence.
"""

from __future__ import annotations

import ctypes
import os
import re
from ctypes import POINTER, c_char_p, c_int, c_uint, c_void_p
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EGL_NONE = 0x3038
EGL_OPENGL_ES_API = 0x30A0
EGL_PLATFORM_SURFACELESS_MESA = 0x31DD
EGL_PBUFFER_BIT = 0x0001
EGL_OPENGL_ES3_BIT = 0x0040
EGL_CONTEXT_CLIENT_VERSION = 0x3098
EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB
GL_VERTEX_SHADER = 0x8B31
GL_FRAGMENT_SHADER = 0x8B30
GL_COMPILE_STATUS = 0x8B81
GL_LINK_STATUS = 0x8B82
GL_VERSION = 0x1F02
GL_NO_ERROR = 0


def extract_constant(source: str, name: str) -> str:
    match = re.search(r"(?:private )?const val " + re.escape(name) + r'\s*=\s*"""(.*?)"""', source, re.S)
    if match is None:
        raise ValueError(f"Shader constant not found: {name}")
    return match.group(1)


class GlesCompiler:
    def __init__(self) -> None:
        os.environ.setdefault("EGL_PLATFORM", "surfaceless")
        os.environ.setdefault("XDG_CACHE_HOME", "/tmp")
        os.environ.setdefault("MESA_GLES_VERSION_OVERRIDE", "3.0")
        self.egl = ctypes.CDLL("libEGL.so.1")
        self.egl.eglGetProcAddress.argtypes = [c_char_p]
        self.egl.eglGetProcAddress.restype = c_void_p
        get_platform = ctypes.CFUNCTYPE(c_void_p, c_uint, c_void_p, POINTER(c_int))(
            self.egl.eglGetProcAddress(b"eglGetPlatformDisplayEXT")
        )
        self.display = get_platform(EGL_PLATFORM_SURFACELESS_MESA, None, None)
        if not self.display:
            raise RuntimeError("EGL_EXT_platform_base surfaceless display is unavailable")

        major, minor = c_int(), c_int()
        if not self._egl("eglInitialize", c_uint, [c_void_p, POINTER(c_int), POINTER(c_int)])(
            self.display, ctypes.byref(major), ctypes.byref(minor)
        ):
            raise RuntimeError("eglInitialize failed for the surfaceless display")
        if not self._egl("eglBindAPI", c_uint, [c_uint])(EGL_OPENGL_ES_API):
            raise RuntimeError("eglBindAPI(OpenGL ES) failed")

        config_attrs = (c_int * 13)(
            0x3033, EGL_PBUFFER_BIT,
            0x3040, EGL_OPENGL_ES3_BIT,
            0x3024, 8,
            0x3023, 8,
            0x3022, 8,
            0x3025, 0,
            EGL_NONE,
        )
        config, config_count = c_void_p(), c_int()
        chose = self._egl(
            "eglChooseConfig",
            c_uint,
            [c_void_p, POINTER(c_int), POINTER(c_void_p), c_int, POINTER(c_int)],
        )(self.display, config_attrs, ctypes.byref(config), 1, ctypes.byref(config_count))
        if not chose or config_count.value < 1:
            raise RuntimeError("No GLES 3 pbuffer EGL config is available")

        pbuffer_attrs = (c_int * 5)(0x3057, 1, 0x3056, 1, EGL_NONE)
        self.surface = self._egl(
            "eglCreatePbufferSurface", c_void_p, [c_void_p, c_void_p, POINTER(c_int)]
        )(self.display, config, pbuffer_attrs)
        context_attrs = (c_int * 5)(EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION_KHR, 0, EGL_NONE)
        self.context = self._egl(
            "eglCreateContext", c_void_p, [c_void_p, c_void_p, c_void_p, POINTER(c_int)]
        )(self.display, config, None, context_attrs)
        if not self.surface or not self.context:
            raise RuntimeError("Could not create the OpenGL ES 3 pbuffer context")
        current = self._egl("eglMakeCurrent", c_uint, [c_void_p, c_void_p, c_void_p, c_void_p])
        if not current(self.display, self.surface, self.surface, self.context):
            raise RuntimeError("eglMakeCurrent failed for the OpenGL ES 3 context")

        self.gl_create_shader = self._gl("glCreateShader", c_uint, [c_uint])
        self.gl_shader_source = self._gl(
            "glShaderSource", None, [c_uint, c_int, POINTER(c_char_p), POINTER(c_int)]
        )
        self.gl_compile_shader = self._gl("glCompileShader", None, [c_uint])
        self.gl_get_shader_iv = self._gl("glGetShaderiv", None, [c_uint, c_uint, POINTER(c_int)])
        self.gl_get_shader_log = self._gl(
            "glGetShaderInfoLog", None, [c_uint, c_int, POINTER(c_int), c_char_p]
        )
        self.gl_create_program = self._gl("glCreateProgram", c_uint, [])
        self.gl_attach_shader = self._gl("glAttachShader", None, [c_uint, c_uint])
        self.gl_link_program = self._gl("glLinkProgram", None, [c_uint])
        self.gl_get_program_iv = self._gl("glGetProgramiv", None, [c_uint, c_uint, POINTER(c_int)])
        self.gl_get_program_log = self._gl(
            "glGetProgramInfoLog", None, [c_uint, c_int, POINTER(c_int), c_char_p]
        )
        self.gl_get_uniform_location = self._gl("glGetUniformLocation", c_int, [c_uint, c_char_p])
        self.gl_get_error = self._gl("glGetError", c_uint, [])
        self.gl_get_string = self._gl("glGetString", c_char_p, [c_uint])
        self.version = self.gl_get_string(GL_VERSION).decode("utf-8", errors="replace")
        self.compiled_sources: set[str] = set()

    def _egl(self, name: str, restype: object, argtypes: list[object]):
        func = getattr(self.egl, name)
        func.restype = restype
        func.argtypes = argtypes
        return func

    def _gl(self, name: str, restype: object, argtypes: list[object]):
        ptr = self.egl.eglGetProcAddress(name.encode("ascii"))
        if not ptr:
            raise RuntimeError(f"GLES entry point unavailable: {name}")
        return ctypes.CFUNCTYPE(restype, *argtypes)(ptr)

    def compile_shader(self, source: str, kind: int, label: str, source_name: str) -> int:
        shader = self.gl_create_shader(kind)
        source_bytes = source.encode("utf-8")
        source_ptr = c_char_p(source_bytes)
        source_len = c_int(len(source_bytes))
        self.gl_shader_source(shader, 1, ctypes.byref(source_ptr), ctypes.byref(source_len))
        self.gl_compile_shader(shader)
        status = c_int()
        self.gl_get_shader_iv(shader, GL_COMPILE_STATUS, ctypes.byref(status))
        if status.value == 0:
            log = ctypes.create_string_buffer(8192)
            length = c_int()
            self.gl_get_shader_log(shader, len(log), ctypes.byref(length), log)
            raise RuntimeError(f"GLSL compile failed [{label}]: {log.value.decode(errors='replace')}")
        self.compiled_sources.add(source_name)
        return shader

    def link_pair(
        self,
        vertex: str,
        fragment: str,
        label: str,
        vertex_name: str,
        fragment_name: str,
    ) -> int:
        vs = self.compile_shader(vertex, GL_VERTEX_SHADER, f"{label}.vert", vertex_name)
        fs = self.compile_shader(fragment, GL_FRAGMENT_SHADER, f"{label}.frag", fragment_name)
        program = self.gl_create_program()
        self.gl_attach_shader(program, vs)
        self.gl_attach_shader(program, fs)
        self.gl_link_program(program)
        status = c_int()
        self.gl_get_program_iv(program, GL_LINK_STATUS, ctypes.byref(status))
        if status.value == 0:
            log = ctypes.create_string_buffer(8192)
            length = c_int()
            self.gl_get_program_log(program, len(log), ctypes.byref(length), log)
            raise RuntimeError(f"GLSL link failed [{label}]: {log.value.decode(errors='replace')}")
        print(f"  [GLES PASS] {label}: compile + link")
        return program


def main() -> int:
    core_src = (ROOT / "app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt").read_text(encoding="utf-8")
    ribbon_src = (ROOT / "app/src/main/java/com/example/orb/gl/RibbonOpticsShaders.kt").read_text(encoding="utf-8")
    core_math = extract_constant(core_src, "GLSL_COMMON_MATH")
    ribbon_math = extract_constant(ribbon_src, "GLSL_INV_SMOOTHSTEP")

    def core(name: str) -> str:
        return extract_constant(core_src, name).replace("$GLSL_COMMON_MATH", core_math)

    def ribbon(name: str) -> str:
        return extract_constant(ribbon_src, name).replace("$GLSL_INV_SMOOTHSTEP", ribbon_math)

    compiler = GlesCompiler()
    print(f"[validate_glsl_es] GLES context: {compiler.version}")
    programs: dict[str, int] = {}
    for vert, frag, label in (
        ("BACKGROUND_VERT", "BACKGROUND_FRAG", "BACKGROUND"),
        ("INNER_FLUID_VERT", "INNER_FLUID_FRAG", "INNER_FLUID"),
        ("CORE_GLASS_VERT", "CORE_GLASS_FRAG", "CORE_GLASS"),
    ):
        programs[label] = compiler.link_pair(core(vert), core(frag), label, vert, frag)
    for vert, frag, label in (
        ("RIBBON_VERT", "RIBBON_FRAG", "RIBBON"),
        ("ENERGY_HIGHLIGHT_VERT", "ENERGY_HIGHLIGHT_FRAG", "ENERGY_HIGHLIGHT"),
        ("BUBBLE_PARTICLE_VERT", "BUBBLE_PARTICLE_FRAG", "BUBBLE_PARTICLE"),
        ("POST_QUAD_VERT", "BLOOM_EXTRACT_FRAG", "BLOOM_EXTRACT"),
        ("POST_QUAD_VERT", "BLOOM_BLUR_FRAG", "BLOOM_BLUR"),
        ("POST_QUAD_VERT", "COMPOSITE_FRAG", "COMPOSITE"),
    ):
        programs[label] = compiler.link_pair(ribbon(vert), ribbon(frag), label, vert, frag)

    yujian_root = ROOT / "app/src/main/assets/yujian_orb_core"
    yujian_vertex = (yujian_root / "YuJianCoreVertex.glsl").read_text(encoding="utf-8")
    yujian_fragment = (yujian_root / "YuJianCoreFragment.glsl").read_text(encoding="utf-8")
    programs["YUJIAN_CORE"] = compiler.link_pair(
        yujian_vertex, yujian_fragment, "YUJIAN_CORE_PASS6", "YuJianCoreVertex.glsl", "YuJianCoreFragment.glsl"
    )

    required_core_uniforms = (
        "uDensityScale",
        "uDensityRange",
        "uGlassOptics",
        "uGlassStructure",
        "uGlassRim",
        "uInterfaceIor",
        "uBeerLambert",
        "uFoldOptics",
        "uKeyLightColor",
        "uWarmRimColor",
        "uCoolRimColor",
        "uEmeraldFillColor",
    )
    core_program = programs["CORE_GLASS"]
    for name in required_core_uniforms:
        location = compiler.gl_get_uniform_location(core_program, name.encode("ascii"))
        if location < 0:
            raise RuntimeError(f"Fitted core uniform is inactive after linking: {name}")
    required_yujian_uniforms = (
        "uBackTex", "uTime", "uWake", "uSphereCenter", "uSphereRadii", "uQuadExtent",
        "uHdrScale", "uCoreCenterViewZ", "uCoreRadiusView", "uProjectionDepth", "uShowSun", "uShowCaustics",
    )
    yujian_program = programs["YUJIAN_CORE"]
    for name in required_yujian_uniforms:
        location = compiler.gl_get_uniform_location(yujian_program, name.encode("ascii"))
        if location < 0:
            raise RuntimeError(f"YuJian Core Pass 6 uniform is inactive after linking: {name}")
    if len(compiler.compiled_sources) != 18:
        raise RuntimeError(f"Expected 18 production shader sources, compiled {len(compiler.compiled_sources)}")
    if compiler.gl_get_error() != GL_NO_ERROR:
        raise RuntimeError("GLES reported an error after production shader compilation/linking")

    print(
        f"[validate_glsl_es] PASS: {len(compiler.compiled_sources)} shader sources compiled, "
        f"{len(programs)} programs linked, {len(required_core_uniforms)} native fitted + "
        f"{len(required_yujian_uniforms)} YuJian uniforms active."
    )
    print("[validate_glsl_es] This is shader compile/link evidence only; it does not claim hardware GPU capture.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
