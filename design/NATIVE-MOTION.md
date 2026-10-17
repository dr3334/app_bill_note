# 原生动效实现

日期：2026-10-18。沿用已确认的墨色清单，不增加系统悬浮权限。以下参数是当前实现，未宣称为荣耀官方推荐值或已通过 100 Pro 真机校准。

| 场景 | 实现 | 行为 |
| --- | --- | --- |
| Dock 切换 | AndroidX SpringAnimation，阻尼比 1、刚度 700 | 从当前值接续，反复点击更新目标；销毁视图取消 |
| 页面过渡 | 200ms，8dp 以内位移与淡入 | 切换直接展示目标页，已有页面保留 RecyclerView 状态 |
| 新账入列 | RecyclerView DiffUtil/DefaultItemAnimator，进入 220ms | Room 发出变更后才更新，动画不参与保存 |
| 应用内提示 | 小范围淡入/位移，独立关闭入口 | 已保存、重复、失败分别使用准确文案；金额随隐私开关更新 |
| 详情编辑 | Material BottomSheet 原生展开、关闭、拖动及返回 | 编辑/待核对共享同一个面板，输入错误留在字段旁 |
| 按压 | 原生 Ripple/Material 按钮状态 | 不缩小或位移触区 |
| 减少动态 | 应用开关＋系统 `ValueAnimator.areAnimatorsEnabled()` | Dock 直接到位，关闭列表和页面位移，底部面板取消自定义窗口动效 |

金额不做跳动滚数，后台事件不默认震动。导入的演示计时任务属于 ViewModel，取消任务停止后续状态推进；真正保存使用数据库事务，失败不会播放成功反馈。

平台依据：[WindowInsets 与系统栏](https://developer.android.com/develop/ui/views/layout/edge-to-edge)、[原生弹簧动画](https://developer.android.com/develop/ui/views/animations/spring-animation)。
