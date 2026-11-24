# Tab1 UI 与第三 Tab 退出产品路线计划（仅方案）

**本轮未执行任何 UI 删除，也没有从代码/资产中移除第三 Tab。** 按本任务冻结边界，保留历史实现、分支与资源。本文件是后续实施清单。

## 当前结构

- `MainActivity.kt:342–368` 有 AI Orb / Task Perimeter / YuJian AI Orb 三个 selector segment。
- `DemoTab.kt:8–12` 有 3 个 Enum。
- `AIOrbViewModel.selectTab` 把第三项路由到 `OrbSceneMode.YUJIAN_HYBRID`。
- `AIOrbScene` 按 `sceneMode + coreMode` 分发；第三 Tab 使用 `YuJianCoreRenderer` 与 `OuterOpticalFieldRenderer`，Tab1/Tab2 保持原 renderer。
- `strings.xml` 有第三 Tab label 和 badge；MainActivity 有 badge UI。

## 后续最小风险顺序

1. 创建**单独工作分支/PR**，在实施前锁定保留策略；本审计分支不执行。
2. 产品路线先移除第三 Tab selector 与 enum/state mapping；保持 Tab1 与 Task Perimeter 入口、默认 `ORB_ONLY` 和共享 `GLSurfaceView` 不变。
3. 删除第三 Tab badge、strings、仅该 Tab 的入口状态及测试。
4. 搜索并分类所有 `YUJIAN_HYBRID`、`YUJIAN_CORE`、`YuJianCoreRenderer`、`OuterOpticalFieldRenderer`、`YUJIAN_*` Capture 字段：确认无 Tab1/Tab2 依赖后才删除可达 runtime；仅当资产/授权/历史保留策略明确时才删除 assets。
5. 保留第三 Tab 历史资源与代码于现有历史分支/归档 commit，方便将来单独恢复；不要 squash 或重写已发布历史。
6. 回归：Tab1 camera/render snapshot 不变；Tab2 photo layout、Task Energy、perimeter WaterLight、调色和生命周期不变；Compose navigation 测试断言仅两个 tab；APK dependency/assets 变化有清单。
7. 若这是“产品路线冻结”而非物理删除，另可隐藏入口、保留 unreachable implementation；此决定需由产品 owner 定义，本轮不擅自选择。

## 不允许据此执行

本审计只给出退出计划，没有删 UI、路由、Shader、assets、Renderer 或测试，也未更改截图与模式。任何实际代码变更应在单独任务里执行并独立验收。
