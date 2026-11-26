"""Compile production GLSL in surfaceless EGL; identify software rendering explicitly."""
import ctypes as c,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def run(draw_callback=None):
 e=c.CDLL('libEGL.so.1');e.eglGetProcAddress.argtypes=[c.c_char_p];e.eglGetProcAddress.restype=c.c_void_p
 def fn(name,result,args):
  ptr=e.eglGetProcAddress(name.encode());assert ptr,name
  return c.CFUNCTYPE(result,*args)(ptr)
 display=fn('eglGetPlatformDisplayEXT',c.c_void_p,[c.c_uint,c.c_void_p,c.POINTER(c.c_int)])(0x31DD,None,None)
 e.eglInitialize.argtypes=[c.c_void_p,c.POINTER(c.c_int),c.POINTER(c.c_int)];e.eglInitialize.restype=c.c_uint
 a=c.c_int();b=c.c_int();assert e.eglInitialize(display,c.byref(a),c.byref(b))
 e.eglBindAPI.argtypes=[c.c_uint];assert e.eglBindAPI(0x30A0)
 attrs=(c.c_int*13)(0x3040,0x40,0x3033,1,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038)
 config=c.c_void_p();count=c.c_int()
 e.eglChooseConfig.argtypes=[c.c_void_p,c.POINTER(c.c_int),c.POINTER(c.c_void_p),c.c_int,c.POINTER(c.c_int)]
 assert e.eglChooseConfig(display,attrs,c.byref(config),1,c.byref(count)) and count.value
 e.eglCreateContext.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.POINTER(c.c_int)];e.eglCreateContext.restype=c.c_void_p
 context=e.eglCreateContext(display,config,None,(c.c_int*3)(0x3098,3,0x3038));assert context
 e.eglMakeCurrent.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.c_void_p];assert e.eglMakeCurrent(display,None,None,context)
 get=fn('glGetString',c.c_char_p,[c.c_uint])
 result=dict(vendor=get(0x1F00).decode(),renderer=get(0x1F01).decode(),version=get(0x1F02).decode())
 create=fn('glCreateShader',c.c_uint,[c.c_uint]);source=fn('glShaderSource',None,[c.c_uint,c.c_int,c.POINTER(c.c_char_p),c.c_void_p]);compile_=fn('glCompileShader',None,[c.c_uint]);status=fn('glGetShaderiv',None,[c.c_uint,c.c_uint,c.POINTER(c.c_int)])
 text=(ROOT/'app/src/main/java/com/example/waterworld/WaterWorldProgram.kt').read_text()
 shaders=[]
 for name,kind in [('VERTEX',0x8B31),('FRAGMENT',0x8B30)]:
  glsl=re.search(r'val '+name+r' = """(.*?)"""',text,re.S).group(1)
  lines=glsl.splitlines();glsl='\n'.join([lines[0]]+[x.strip() for x in lines[1:]])
  shader=create(kind);s=c.c_char_p(glsl.encode());source(shader,1,c.byref(s),None);compile_(shader);ok=c.c_int();status(shader,0x8B81,c.byref(ok));assert ok.value,name
  shaders.append(shader)
 program=fn('glCreateProgram',c.c_uint,[])();attach=fn('glAttachShader',None,[c.c_uint,c.c_uint])
 for shader in shaders:attach(program,shader)
 fn('glLinkProgram',None,[c.c_uint])(program);ok=c.c_int();fn('glGetProgramiv',None,[c.c_uint,c.c_uint,c.POINTER(c.c_int)])(program,0x8B82,c.byref(ok));assert ok.value
 result.update(shader_compile='PASS',program_link='PASS',gpu_scene_assembly='NOT_RUN',classification='SOFTWARE_GLES_DIAGNOSTIC' if any(s in result['renderer'].lower() for s in ['llvmpipe','swiftshader','softpipe']) else 'UNCLASSIFIED')
 if draw_callback: result.update(draw_callback(fn,program))
 print(json.dumps(result,indent=2))
 return result
if __name__=='__main__':run()
