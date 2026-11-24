# P0-A 输入与现有渲染路径审计

## 锁定基线

| 项目 | 实际值 | 状态 |
|---|---|---|
| AI Orb Demo Main | `07ce54a6657edc57b2106d734b57ca3cec87545e` | 已重新查询远端 |
| P0-4 分支基线 | `a8e9249da74784fdf89cb08ec089ae9b676eb45a` | 本任务分支起点 |
| P0-3 冻结合同 | `aa0729887570db4a0da0d3fb7a629a1a7273666ba0c7ab129b60d70bfdee9170` | 哈希通过 |
| REF_B | `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` | 1254×1254 RGB，哈希通过 |
| YuJian_App Main | `03db3692669847084d7e48fc3128f85cbf832af9` | P0-4 审计时一致，源仓库只读 |
| 本任务分支 | `codex/tab3-refb-outer-optical-field-v1` | 从 P0-4 基线建立，未改 Main/PR #1 |

远端目前没有 Main 新提交。现有 PR #1 保持打开。本轮的提取输出不修改 P0-3 的原图、ROI、Mask、合同或阈值。

## P0-A 提取结果

- `P0_A_PREFLIGHT_PASS.json`：PASS。
- 冻结视觉区域：13；Mask：12；逐项 SHA、尺寸、像素数与 Manifest 一致。
- 合同声明 5 个 Resident 身份、冻结 Mask 仅有 3 个聚合语义组；冻结中心线共有 7 段折线片段。
- 7 个路径片段形成 7 个二维投影交叉候选；交叉不等于三维分支或真实遮挡。
- 固定粒子 Mask 中按最大相邻 log 面积间距分出 5 个大水滴候选和 10 个小粒子/高光片段；背景 Bokeh Mask 有 12 个标注连通区。
- 颜色、线性亮度、局部对比度、梯度、区域面积、归一化包围框、中心线曲率、ROI 内横截面信号宽度、边缘/光丝候选、高亮候选均由原图生成并保留算法阈值。
- 叠加图与图集已对照原始 REF_B 复核；输出画布坐标保持 1254×1254，无配准、缩放或变形。

## 静态图证据边界

`OBSERVED` 包含源像素统计、Mask 交叠、冻结像素路径、局部信号组件及二维坐标。五个 Resident 名称、运动周期和前后角色来自冻结 P0-3 合同，归类为 `INFERRED_FROM_CONTRACT`。REF_B 只有一帧 RGB，不能唯一求出实体 Z、透明曲面厚度、折射路径、身份级遮挡、动态流向或相位；这些字段在图和合同中为 `UNCERTAIN`，不会写成测量值。

## 当前 Tab3 渲染路径（修改外围前）

| 范围 | 基线源码路径 | P0-A 时状态 |
|---|---|---|
| Tab 1 | `AIOrbScene.renderOriginalOrbOnlyFrame` → Background → Particles → `AIOrbOrbitRibbons` back pass → Highlights → refraction snapshot → 原生 Core → Ribbons front pass → Highlights/Particles → PostProcess | 生产路径冻结；本分支不改 Tab1 参数 |
| Tab 2 | `AIOrbScene.renderTaskPerimeterDemoFrame` → Photo/Task/Perimeter pipeline | 生产路径冻结；本分支不改 Tab2/KF01–KF06 |
| Tab 3 | 同一个 GLSurfaceView/EGL 上下文与上述 Tab1 外围 Pass；Pass 6 用 `YuJianCoreRenderer` 替换原生 Core | 本轮基线已存在，外围仍是旧 Demo Pass，等待 P0-A 完成后重构 |
| YuJian Core | `YuJianCoreRenderer.render` 采样 Pass 5 完成纹理；Core-only ESSL3 分析球体 | 与 Tab1/Tab2共享 GL 宿主，无第二个 EGL/TextureView |

YuJian 的 `SPHERE_FS` 是屏幕空间解析球体 Shader，不采样 `processing_water_core_v1.webp`，也不是 3D 网格或 3D 体积 Raymarch；此事实见 `docs/yujian_hybrid/YUJIAN_CORE_EXTRACTION_AUDIT.md`。新外围 Pass 将仅替换 Tab3 分支，不能把该事实扩写成“真实 3D Core”。

## P0-A 门禁结论

`REF_B_SOURCE_VERIFICATION.json`、`REF_B_STRUCTURE_INVENTORY.json`、`REF_B_GEOMETRY_DATA.json`、`REF_B_COLOR_MATERIAL_DATA.json`、`REF_B_DEPTH_OCCLUSION_GRAPH.json`、`OUTER_OPTICAL_FIELD_CONTRACT_V1.json` 及五张检查图已完成。独立校验、Schema、自身坐标检查和 P0-3 Mask 校验全部通过。**P0-A PASS，允许开始 Tab3-only 外围 Renderer 开发。**

P0-A PASS 只代表视觉输入已提取、合同可机读；它不代表新外围 Shader、API36 GPU、动态或视觉保真验收通过。
