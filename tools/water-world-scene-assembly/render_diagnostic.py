"""Execute the shared scene bindings and production GLSL using software GLES, not PIL compositing."""
import ctypes as c,hashlib,io,json,zipfile
from pathlib import Path
from PIL import Image
from probe_gles import run,ROOT
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'
def render(fn,program):
 def call(name,ret,args,*values): return fn(name,ret,args)(*values)
 U=c.c_uint; I=c.c_int; F=c.c_float; P=c.c_void_p
 def gen(name):
  value=U();call(name,None,[I,c.POINTER(U)],1,c.byref(value));return value.value
 def bind(name,target,value):call(name,None,[U,U],target,value)
 texture=gen('glGenTextures');bind('glBindTexture',0x0DE1,texture)
 call('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x8058,1254,1254,0,0x1908,0x1401,None)
 fbo=gen('glGenFramebuffers');bind('glBindFramebuffer',0x8D40,fbo)
 call('glFramebufferTexture2D',None,[U,U,U,U,I],0x8D40,0x8CE0,0x0DE1,texture,0)
 depth=gen('glGenRenderbuffers');bind('glBindRenderbuffer',0x8D41,depth)
 call('glRenderbufferStorage',None,[U,U,I,I],0x8D41,0x81A6,1254,1254)
 call('glFramebufferRenderbuffer',None,[U,U,U,U],0x8D40,0x8D00,0x8D41,depth)
 assert call('glCheckFramebufferStatus',U,[U],0x8D40)==0x8CD5
 call('glViewport',None,[I,I,I,I],0,0,1254,1254);call('glClearColor',None,[F,F,F,F],0,0,0,1);call('glClear',None,[U],0x4100)
 call('glEnable',None,[U],0x0B71);call('glDepthFunc',None,[U],0x0203);call('glEnable',None,[U],0x0BE2)
 call('glBlendFuncSeparate',None,[U,U,U,U],0x0302,0x0303,1,0x0303);call('glUseProgram',None,[U],program)
 def loc(name):
  v=call('glGetUniformLocation',I,[U,c.c_char_p],program,name.encode());assert v>=0,name;return v
 call('glUniform2f',None,[I,F,F],loc('uCanvas'),1254,1254);call('glUniform1i',None,[I,I],loc('uTexture'),0)
 lighting=json.loads((OUT/'02_SUPPLEMENTED_ASSETS/lighting/lighting.json').read_text())
 key_light=lighting['key']; light_color=[v*key_light['intensity'] for v in key_light['color']]
 materials=json.loads((OUT/'02_SUPPLEMENTED_ASSETS/materials/materials.json').read_text());draws=json.loads((OUT/'03_SCENE_GRAPH/draw_bindings.json').read_text())['draws']
 with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
  for d in draws:
   m=json.loads((OUT/d['mesh_path']).read_text());v=(F*(len(m['vertices'])*8))(*[x for row in m['vertices'] for x in row]);indices=(U*len(m['indices']))(*m['indices'])
   vao=gen('glGenVertexArrays');call('glBindVertexArray',None,[U],vao)
   for target,data in [(0x8892,v),(0x8893,indices)]:
    b=gen('glGenBuffers');bind('glBindBuffer',target,b);call('glBufferData',None,[U,c.c_ssize_t,P,U],target,c.sizeof(data),data,0x88E4)
   for i,size,offset in [(0,3,0),(1,3,12),(2,2,24)]:
    call('glEnableVertexAttribArray',None,[U],i);call('glVertexAttribPointer',None,[U,I,U,c.c_ubyte,I,P],i,size,0x1406,0,32,c.c_void_p(offset))
   t=gen('glGenTextures');bind('glBindTexture',0x0DE1,t)
   if d['texture_path']:
    raw=z.read('WATER_WORLD_V2/'+d['texture_path'].removeprefix('01_REUSED_ASSETS/'));assert hashlib.sha256(raw).hexdigest()==d['texture_sha256'];im=Image.open(io.BytesIO(raw)).convert('RGBA')
   else:im=Image.new('RGBA',(1,1),'white')
   data=im.tobytes();call('glTexImage2D',None,[U,I,I,I,I,I,U,U,P],0x0DE1,0,0x8058,im.width,im.height,0,0x1908,0x1401,c.c_char_p(data))
   for key,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:call('glTexParameteri',None,[U,U,I],0x0DE1,key,value)
   mat=materials[d['material']];tint=d.get('tint',mat['base_color']+[mat['alpha']])
   call('glUniformMatrix4fv',None,[I,I,c.c_ubyte,c.POINTER(F)],loc('uModel'),1,0,(F*16)(*d['model']))
   for name,values in [('uTint',tint),('uLightDirection',key_light['direction']),('uLightColor',light_color)]:
    call('glUniform'+str(len(values))+'fv',None,[I,I,c.POINTER(F)],loc(name),1,(F*len(values))(*values))
   for name,value in [('uShellSide',d.get('shell_side',0)),('uFresnel',mat['fresnel']),('uAmbient',d['ambient']),('uEmission',mat['emission']),('uUseTexture',1 if d['texture_path'] else 0)]:call('glUniform1f',None,[I,F],loc(name),value)
   call('glDepthMask',None,[c.c_ubyte],int(d['depth_write']));call('glDrawElements',None,[U,I,U,P],4,len(indices),0x1405,None)
   assert call('glGetError',U,[])==0,d['draw_id']
 pixels=(c.c_ubyte*(1254*1254*4))();call('glReadPixels',None,[I,I,I,I,U,U,P],0,0,1254,1254,0x1908,0x1401,pixels);assert call('glGetError',U,[])==0
 path=OUT/'05_VISUAL_VALIDATION/software_gles_assembled_scene.png'
 Image.frombytes('RGBA',(1254,1254),bytes(pixels)).transpose(Image.Transpose.FLIP_TOP_BOTTOM).save(path)
 return dict(draw_count=len(draws),fbo='COMPLETE',gl_error=0,png_sha256=hashlib.sha256(path.read_bytes()).hexdigest(),visual_acceptance='NOT_EVALUATED')
if __name__=='__main__':
 result=run(render);(OUT/'05_VISUAL_VALIDATION/SOFTWARE_GLES_TELEMETRY.json').write_text(json.dumps(result,indent=2)+'\n')
