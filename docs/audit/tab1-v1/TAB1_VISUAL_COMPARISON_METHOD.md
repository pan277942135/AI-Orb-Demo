# 视觉对照图方法

- REF_B 及 `mode_06_FULL_RENDER.png` 原图都为 1254×1254；图像主体区域不缩放、不裁切、不平移、不重投影。
- 并排图只添加标题栏和间隔。
- `TAB1_PIXELWISE_DIFF_X2_5.png` 为 `min(abs(REF_B−GPU)×2.5,255)` 的 RGB 差异显示；×2.5 只为看清差异，不等于任何验收分数。
- ROI 叠加把原始冻结 REF_B 三类 Resident mask 按原像素坐标同时叠到参考和候选图上。未重新拟合 mask，也没有针对候选图重新测量。
- SSIM / ΔE 和 ROI 数值来自 Exact-HEAD CI 中的 `P04_METRIC_VALIDATION_REPORT.json`，没有在此处重算或用视觉差异图替代。
- `TAB1_NATIVE_ORB_GPU.png` 是含 Android 系统 toast 的 UI 截图，不放进对照图；对照只用真正的零 UI FBO capture `mode_06_FULL_RENDER.png`。
