# REF_B 视觉结构分析与 Tab1 对照

## 唯一主参考与坐标

- 主场景：`docs/p0_3/references/REF_B_FULL_SCENE_RGB.png`，1254×1254 RGB，SHA256 `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`。
- REF_A：`REF_A_CORE_CRYSTAL_RGBA.png`，1254×1254 RGBA，SHA256 `2773e4628ff6132605314693742488542c3e70fd04b581d8825dd13cea0067ee`。只用于 Core 几何/材质 ROI；不要拿 REF_A 做全场对照。
- Acceptance contract SHA256：`aa0729887570db4a0da0d3fb7a629a1a7273666ba0c7ab129b60bfdee9170`；12 masks 已冻结验证，PF01 PASS。
- Candidate：Exact-HEAD REF_B full-scene `mode_06_FULL_RENDER.png`，1254×1254 FBO readback。没有平移、尺度变换、配准或 warp；并排及差异图直接使用相同像素坐标。

## 视觉结构观察

### 母版 REF_B

- 主体由清透厚壁的多层晶体壳包裹；中心为可读的深蓝/青蓝体积和局部水光折叠，区域形态不呈大块均匀烟雾。
- 金色与冰青色丝带分属不同深度，沿主体外围与前景交错；可见交叉但不会完全合并成一块宽平面。
- 明亮折射/焦散集中在边缘与特定结构交会处；背景保持暗青深度，水滴作为局部点状元素。
- 图像含三组 Resident 语义 mask，并不是五条可逐像素辨识的路径；不能单凭此静帧反推 5 条真实 3D 曲线、Z 或运动周期。

### Exact-HEAD FULL_RENDER 软件 GLES

- 具有可见透明外壳、青/绿体积、亮金/青 Ribbon 和背景粒子，但材料分区与母版差异大；核心呈大块青绿/蓝色团块和暗色区域，纹理频率、形状分布不同。
- 顶部中央出现明显闭合的青蓝椭圆；同形结构在 `CAVITY_ONLY` 中仍存在。
- 前景青白带横穿核心下半部，宽绿弧占据右/左外圈，金色弧和 flare 重叠。视觉上核心和 Resident 的层级权重偏移。
- `BLOOM_OFF` 与 `FULL_RENDER` 的实际 PNG hash 相同；本次 capture 中开关并未改变输出，因此不能说 Bloom 造成这些结构。

## 量化证据与口径

**REF_B 主全场对比**（冻结脚本、同尺寸且不配准）：SSIM 0.462767；ΔE2000 median 13.0379、P90 36.1959。contract 分别要求 ≥0.82、≤8、≤15，全部失败。

**REF_A 隔离 Core 对比**（非 REF_B 全场指标）：alpha silhouette IoU 0.43219、center error 0.04783D、直径相对误差 0.34469。面积误差：Emerald +127.55%，Sapphire −44.23%，Aqua −99.15%；Centroid error：Emerald 0.02616D、Sapphire 0.08076D、Aqua 0.25774D。不能把这些数写成 REF_B full-scene score。

REF_B contract 的 full-scene target sphere diameter ratio 是 0.51994，对 1254 px 画布约 652 px；候选 CORE_ONLY alpha bbox 是 654 px。这个派生尺寸检查不是冻结 silhouette gate，也没有 REF_B 独立外壳 mask，不能用它抵消 REF_A silhouette 的正式失败。两个比较 scope 有不同参考图与语义，下一轮必须先固定截取物/相机合同，不能改 mask 或把 ROI 注册到候选画面。

## 取证图

- [REF_B 与 FULL_RENDER 并排](TAB1_REF_B_VS_GPU.png)：两侧图像区域原生 1254×1254，无缩放变形。
- [像素差异 ×2.5](TAB1_PIXELWISE_DIFF_X2_5.png)：直接对齐逐像素 RGB absolute difference，只为可视化放大，不是质量分数。
- [冻结 Resident masks 叠加](TAB1_FROZEN_REF_B_ROI_OVERLAY.png)：REF_B mask 原坐标直接叠到 REF_B 与候选 FULL_RENDER；未重新定位或拟合。
- [原始 Full Render PNG](evidence/mode_06_FULL_RENDER.png)、[Cavity Only](evidence/mode_02_CAVITY_ONLY.png)、[Residents Only](evidence/mode_05_RESIDENTS_ONLY.png)。

## 解释边界

所有 GPU 图像都是 API36 AVD 的 SwiftShader 软件 GLES FBO 导出。它们足以说明候选 Shader/资产在该确定后端下存在明显差异，不足以证明 HUAWEI CMS-AL20 或其他硬件 GPU 的输出/帧率。
