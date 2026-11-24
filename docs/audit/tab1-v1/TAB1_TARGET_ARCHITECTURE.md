# Tab1 重构目标架构（冻结方案）

本文件冻结下一轮设计方向，不在本审计中实施。

## 设计不变量

- 仍只有一个 `AIOrbScene`、一个 `AIOrbCore`、一个 `OrbFluidVolumeRenderer` 和唯一生产 3D WebP Volume。
- 继续使用 Master Camera 与同一 Motion/Capture Snapshot；不能以换相机、改变整球位置/大小、换母版或重画 mask 解决误差。
- Tab2、照片与 Task Perimeter 保持独立路径；Tab3 退出路线另列计划，不在内核 PR 中删除。
- 保留 3D Core rotation、原生 Volume、Cavity SDF、Beer–Lambert、HDR 和 Master 参数源。

## 推荐 Pass 图

1. **Scene-linear setup**：一个确定的 linear HDR 场景颜色空间、曝光预算、相机与深度合同；导出实际 uniform snapshot。
2. **Back layers**：Background、background particles、后层 Resident 写入可采样 color/depth layer。
3. **Optical scene snapshot**：建立后景折射源；标出物体深度和材质类别。
4. **Outer shell**：独立评估外表面法线、Fresnel、环境反射、双界面透射；输出 shell response 与可复用 transmission，不先把整幅场景不透明混成一个颜色。
5. **Cavity and liquid**：保留同一 3D Volume 与旋转矩阵；明确 cavity clipping、lens depth、surface normal、volume sample coordinate；每个步骤可独立 capture。
6. **Integration**：每条光路只计算一次 Beer–Lambert 和透射；光照/吸收受结构与深度控制；caustics 沿真实体积路径积分，随 density 和 attenuation 变化。
7. **Front layers**：按物理深度合成前景 Resident；区分遮挡、穿越 glass 的折射与直接前景绘制，禁止一个全亮色平面覆盖整个 Core。
8. **Post**：最后做 bloom、ACES、sRGB。Bloom-off 必须仍能看清晶体壳、Sapphire 暗谷、Emerald fold、Aqua 局部和焦散。
9. **Evidence**：以单一 snapshot 原子导出 Cavity/Volume/Glass/Resident/Bloom-Off/Full/PRE/POST 与 runtime SHA、uniform、格式、GL errors。

## Lens 与 cavity 目标

Lens 的界面必须是 cavity 内真实嵌合的局部曲面，不形成闭合椭圆线框或一枚独立悬浮椭球。把 geometry、surface normal、intersection depth、ray transmission 和 rim response 放在同一空间合同中；由 CAVITY_ONLY、LENS_DEPTH/NORMAL、GLASS_ONLY 与 FULL_RENDER 同 snapshot 逐级验收。REF_A embedded-lens mask 与 REF_B full composition 使用现有冻结语义，不添加新分类去绕过失败。

## Resident integration

对现有五个身份分别保留 Path/Width/Period/Phase/Depth/Color/Material。先恢复 REF_B 三组冻结区域边缘，再补逐身份空间诊断；不把三组 mask 误当五身份真值。Back 与 Front 都进入可解释的 depth-aware compositor；独立确认发光强度和 glass interaction。

## Debug/Telemetry 契约

- Diagnostics 描述真正执行的 draw pass，测试其 manifest 与 GL frame readback 相符。
- 生产模式高保真帧要求 Volume `LOADED`, `uVolumeLoaded=1`, 4 textures valid，shader SHA/build SHA 匹配。
- 每项 sensor/renderer 信息分清 CPU timing、GPU timing、设备、AVD、SwiftShader；缺失必须 `UNAVAILABLE`。
