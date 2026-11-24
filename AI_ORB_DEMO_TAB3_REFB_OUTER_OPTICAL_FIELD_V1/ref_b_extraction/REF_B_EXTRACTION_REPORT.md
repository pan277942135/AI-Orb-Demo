# REF_B 全量结构提取报告

## 输入锁定

- REF_B：1254×1254 RGB，SHA256 `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`，原图按原始字节读取，未改写。
- P0-3 合同 SHA256：`aa0729887570db4a0da0d3fb7a629a1a7273666ba0c7ab129b60d70bfdee9170`；合同状态 `FROZEN`。
- 附件 ZIP SHA256：`9e4d1b3230c9404814d6f23974371f33c4c9fc770788472070108ce6ea95f3b0`。附件 ZIP 含原始合同与 REF_B；完整 12 张派生 Mask 由仓库的冻结 Manifest 提供并逐张核验。
- P0-3 ROI：13 项，Mask：12 张；原始阈值与 Mask 均未修改。

## 提取方法与证据等级

本次将冻结 Mask 作为语义锚点；同时从原始 RGB/sRGB 解码得到线性亮度、色度、局部对比度、边缘梯度、连通分量和横截面色度宽度。所有附加统计均为可重复的检测结果，不覆盖原 Mask。中心线点由冻结 P0-3 Mask 生成器的 Python AST 字面量读取，未重新绘制或重拟合。

- `OBSERVED`：像素位置、Mask 交叠、冻结中心线、颜色/亮度/梯度统计、Mask 内连通区域。
- `INFERRED`：P0-3 合同的五个 Resident 身份、材质身份和前后角色。
- `UNCERTAIN`：三维深度距离、实际透明曲面边界、G01/G02/A01 在聚合 Mask 内的精确分离、单帧动态方向与速度。

## 结构盘点

- 冻结合同声明 5 个 Resident 身份、3 个外围 Resident 语义 Mask。
- 冻结的像素路径标注由 7 个折线段组成：按组 `{"RESIDENT_FRONT_CYAN": 1, "RESIDENT_FRONT_GOLD": 2, "RESIDENT_BACK_GREEN_GOLD": 4}`。这些是路径段，不能当作 7 个独立三维对象。
- Droplets/Particles 冻结 Mask 内，按最大相邻 log-area 间距自动分成 5 个大水滴候选与 10 个细粒/高光片段。
- Background Bokeh 冻结 Mask 含 12 个连通注释区域。
- 主体曲面、内部光丝和高光节点数量另以 Otsu 色度组件、梯度与亮度分布给出；连通片数会受遮挡、相交和算法阈值影响，不冒充物理对象数量。

## 数据与审查

- 几何、宽度、曲率、归一化边界：`REF_B_GEOMETRY_DATA.json`。
- 色彩、亮度、饱和度、色相分布：`REF_B_COLOR_MATERIAL_DATA.json`。
- 投影重叠与推断深度关系：`REF_B_DEPTH_OCCLUSION_GRAPH.json`。
- 结构总图/密度/色彩/图集：`REF_B_OUTER_OPTICAL_STRUCTURE_MAP.json`、`REF_B_VISUAL_DENSITY_MAP.png`、`REF_B_COLOR_DISTRIBUTION.png`、`REF_B_GEOMETRY_OVERLAY.png`、`REF_B_STRUCTURE_ATLAS.png`。
- 新合同中的目标值直接引用 REF_B 测量数据；没有新增任意视觉阈值，P0-3 原阈值仍保持权威。

独立校验脚本将核对输入 SHA、12 张 Mask、13 区映射、Schema、所有可视化画布尺寸、坐标范围和合同字节一致性。静态图无法证明唯一 3D 深度或运动真值，这些字段已明确标为推断或不确定。

### 补充的对象级检测

- Otsu 色度组件、局部梯度/高频边缘碎片及线性亮度 P99.5 局部高光候选按三组 Resident ROI 分别记录；像素位置、面积、主方向、轴向尺度和平均 RGB 在 Geometry Data 中。碎片计数不等同于物理光丝/光源数量。
- 七个冻结折线段中，找到 7 个二维交叉候选；它们不证明真实三维分支或遮挡顺序。
- 横截面信号宽度取自原图线性化色度分数，沿冻结中心线法线采样，并被原冻结语义 ROI 限定；完整透明表面边界仍不可由单帧 RGB 唯一测得。

## P0-A 独立复核结果

`P0_A_PREFLIGHT_PASS.json` 二次核验通过：原图/合同/Mask SHA、13 区映射、外层 JSON Schema、对象计数、坐标界限、对齐可视化画布和原始输入未变均为 PASS。视觉复核确认叠加路径与冻结图中的三组主体区域、12 个背景散景注释和 5 个大水滴候选对齐；细粒与高光组件按冻结 Mask 分列。三维深度和单帧动态保持 UNCERTAIN。
