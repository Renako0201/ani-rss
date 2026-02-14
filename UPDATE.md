- release: v2.5.4.0
- refactor: 优化管理列表加载
- refactor: 优化删除确认框
- refactor: 优化 OpenList 上传
- feat: 新增 Rclone 远程同步任务（SSH + rclone rc），支持番剧/合集场景。
- feat: 新增 Rclone 同步任务面板，支持状态查看、速度展示与任务清理。
- feat: 下载流程增加与 Rclone 同步队列联动，避免连续订阅时并发冲突。
- fix: 优化下载日志与并发表现（补充重试/磁力准备日志，移除 getMagnet 同步锁）。
- fix: 优化 OpenList 下载稳定性（API 超时控制、轮询节流、关键流程日志增强）。
- fix: 修复打开“管理”弹窗时背景页闪黑（避免打开时强制刷新主列表）。
- fix: 修复打开弹窗时列表网格宽度抖动的问题（关闭 Dialog 的 body 锁滚动补偿）。
- fix: 修复通知配置页渲染异常，Rclone 配置仅在 Rclone 通知类型下显示。
- fix: 修复多处中文文案和编码问题，统一使用 UTF-8。

[请不要将本项目在国内宣传](https://github.com/wushuo894/ani-rss/discussions/504)

[从1.0升级至2.0的配置继承](https://github.com/wushuo894/ani-rss/discussions/427)


