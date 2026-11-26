"""Fixed-canvas diagnostic comparison. Image metrics do not invent a six-axis acceptance score."""
import hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image,ImageFilter
from scipy.ndimage import uniform_filter
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/05_VISUAL_VALIDATION'
MASTER=ROOT/'releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png'
def ssim(a,b):
 a=a.mean(axis=2)/255;b=b.mean(axis=2)/255
 ma=uniform_filter(a,11);mb=uniform_filter(b,11)
 va=uniform_filter(a*a,11)-ma*ma;vb=uniform_filter(b*b,11)-mb*mb;cov=uniform_filter(a*b,11)-ma*mb
 return float((((2*ma*mb+.01**2)*(2*cov+.03**2))/((ma*ma+mb*mb+.01**2)*(va+vb+.03**2))).mean())
def run():
 assert hashlib.sha256(MASTER.read_bytes()).hexdigest()=='fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589'
 m=Image.open(MASTER).convert('RGB');r=Image.open(OUT/'software_gles_assembled_scene.png').convert('RGB');assert m.size==r.size==(1254,1254)
 a=np.asarray(m,dtype=float);b=np.asarray(r,dtype=float)
 regions={'full':[0,0,1254,1254],'sphere':[204,174,1049,1019],'fish':[480,440,890,695],'left_plant':[235,425,426,882],'right_plant':[825,520,969,850],'rocks':[356,712,896,920]}
 metrics={}
 for name,(x,y,xx,yy) in regions.items():
  aa=a[y:yy,x:xx];bb=b[y:yy,x:xx];metrics[name]=dict(rgb_mae=float(abs(aa-bb).mean()/255),ssim_luminance=ssim(aa,bb))
 output=dict(master_sha256=hashlib.sha256(MASTER.read_bytes()).hexdigest(),render_sha256=hashlib.sha256((OUT/'software_gles_assembled_scene.png').read_bytes()).hexdigest(),classification='SOFTWARE_GLES_DIAGNOSTIC',coordinate_space='1254x1254 unchanged source canvas',metrics=metrics,lpips='UNAVAILABLE_NOT_INSTALLED',six_dimension_scores={k:None for k in ['composition','shape','lighting','depth','material','details']},total_score=None,visual_acceptance='NOT_EVALUATED',reason='No frozen six-axis scoring calibration or complete optical passes; objective metrics alone cannot establish 80 percent.',work2_static_input_ready=False)
 (OUT/'visual_score.json').write_text(json.dumps(output,indent=2)+'\n')
 canvas=Image.new('RGB',(2508,1254));canvas.paste(m,(0,0));canvas.paste(r,(1254,0));canvas.save(OUT/'side_by_side.png');Image.blend(m,r,.5).save(OUT/'overlay.png')
 d=abs(a-b).mean(axis=2)/255;h=np.stack([np.minimum(1,d*3),np.maximum(0,1-abs(d-.3)*4)*.3,np.zeros_like(d)],axis=2);Image.fromarray((h*255).astype('uint8')).save(OUT/'difference_heatmap.png')
 print(json.dumps(output,indent=2))
if __name__=='__main__':run()
