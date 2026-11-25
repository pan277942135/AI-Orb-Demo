# WATER WORLD V2.1 — Work 1.5 最终交付报告

## 1. 最终状态

**`WORK1_5_VALIDATION_FAILED`**。母版源文件已取得并通过四项硬校验；静态资源组完成了测量、独立重组和比较。但鱼轮廓、C01 Resident、植物及岩石指标未通过冻结阈值，因此不得报告 CLOSED。母版源文件阻塞已解除。

本次静态重组为 CPU 合成，不是 GPU 输出；不代表动态运动或设备验收。

## 2. 母版来源与身份

- Repository: `pan277942135/AI-Orb-Demo`
- Branch / commit: `assets/water-world-v2-v21-calibration` / `1662bbf9de671b0bb1fa5ce1891a1df3e49703b6`
- Path: `releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png`
- Local copy: `inputs/master/奇幻水晶球中的水下生态世界.png`
- Bytes: `2,256,234`; dimensions: `1254×1254`; mode: `RGB`; alpha: none; ICC: none
- SHA-256: `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589`
- PNG signature, full decode, bytes, dimensions, mode and hash: **PASS** (`00_MASTER_METADATA/master_source_validation.json`)

## 3. 源驱动测量与校准

Sphere 外轮廓用 1,440 角度 Sobel 边缘采样和鲁棒圆拟合：中心 `(626.48, 596.34) px`，半径 `422.30 px`，拟合 p95 残差 `2.00 px`。内核边界受折射和高光影响，置信度 MEDIUM_LOW。

四条鱼按母版逐条登记轮廓框、中心、朝向和位置；暖色像素掩码不覆盖冷色半透明鱼鳍，因此鱼形 IoU 数字是保守的暖色像素支持度对全精灵比较，按 FAIL 处理，不伪称完整轮廓 PASS。五条 Resident 以颜色/亮度像素在路径邻域追踪；同色交叉和部分缺口保留置信标记。植物和岩石边缘有半透明叶片、接触阴影和苔藓混叠，区域掩码置信度 MEDIUM_LOW。

独立资源决策：65 个 V2 原始文件 KEEP、逐字节复制；鱼、Resident、球体/玻璃、植物、岩石摆位与几何使用新的 RECALIBRATE_COPY；REPAIR=0，REGENERATE=0。原始 V2 ZIP 未改动，V2 原始文件副本核对 `65/65` SHA 匹配。

## 4. 视觉指标

| 指标 | 实测 | 门槛 | 状态 |
|---|---:|---:|---|
| Sphere IoU | 0.9948 | ≥0.98 | PASS |
| Sphere 中心误差 / 直径 | 0.00105 | ≤0.005 | PASS |
| Sphere 直径误差 | 0.00045 | ≤0.01 | PASS |
| Fish IoU | `{"WW_FISH_01":0.4189,"WW_FISH_02":0.3070,"WW_FISH_03":0.3443,"WW_FISH_04":0.4106}` | 每条 ≥0.90 | FAIL |
| Fish 中心误差 / 画布 | `{"WW_FISH_01":0.00148,"WW_FISH_02":0.00305,"WW_FISH_03":0.00134,"WW_FISH_04":0.00076}` | 每条 ≤0.01 | PASS |
| Resident 双向 Chamfer / 半径 | `{"G01":0.01357,"G02":0.00910,"A01":0.01314,"A02":0.00985,"C01":0.02832}` | 每条 ≤0.015 | FAIL（C01 超标） |
| Resident 宽度误差 | `{"G01":0.0343,"G02":0.0302,"A01":0,"A02":0,"C01":0}` | 每条 ≤0.05 | PASS |
| Plant IoU | `{"plant_left":0.1197,"plant_right":0.1867}` | 每株 ≥0.85 | FAIL |
| Rock IoU | `{"rock_01":0.6290,"rock_02":0.4659,"rock_03":0.3269}` | 每块 ≥0.85 | FAIL |

对比图：`03_STATIC_RECOMPOSITION/master_reference.png`、`static_recomposition.png`、`master_overlay.png`、`difference_heatmap.png`、`region_comparison.png`。母版仅作为比较输入，从未被贴入重组图。

## 5. Motion 与 GPU 合同

47 个 Motion 元素、47 节点、28 条依赖和 21/21 Validator 测试沿用此前 HEAD 的已验证记录，本轮遵照要求没有重跑。动态参数继续保持 null；本轮没有动态视频、GPU 设备帧、APK 或 API36 验收。五条 Resident 的动态交叠与时间维度未验证。

## 6. 资源包

- File: `WATER_WORLD_V2_1_ASSETS.zip`（本报告同级交付）
- 内容：65 个原始 V2 文件副本、已验证母版、分析掩码、校准 JSON、CPU 静态重组、Overlay/Heatmap/指标、Motion 和 GPU 输入合同、验证报告。
- ZIP CRC 与包内 SHA manifest 在构建后逐项核验。
- ZIP 大小 / SHA-256: 见同级 `WATER_WORLD_V2_1_ASSETS.zip.sha256`；不在 ZIP 内自包含自身哈希。

## 7. GitHub

现有 PR #3 保持 Draft/Open，分支 `assets/water-world-v2-v21-calibration`；新增完整资源包和证据，不创建 PR，不改 Main 或 PR #2。最终 Commit、远端 HEAD 和 PR URL 以 GitHub 提交记录为准。

## 8. 验收结论

静态资源和对比产物已实际生成，母版阻塞已解除；冻结视觉门槛仍有失败项，最终为 **`WORK1_5_VALIDATION_FAILED`**。
