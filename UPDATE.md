- release: v2.5.5.0
- feat: 新增 Bangumi 收藏状态同步接口，支持想看/看过/在看/搁置/抛弃状态映射。
- feat: 在 Mikan 添加页条目旁展示 Bangumi 账户状态标签。
- feat: 新增 mikanId -> Bangumi subjectId 转换链路，解决 Mikan 与 Bangumi ID 不一致导致状态不显示的问题。
- refactor: 优化 Mikan/Bangumi 状态同步请求链路，新增 `collectionsMap` 单接口聚合拉取。
- perf: 新增多层缓存（后端内存缓存 + 前端 localStorage 缓存），显著减少重复请求与等待时间。
- fix: 修复 Mikan 页面 Bangumi 状态偶发不显示与匹配失败问题。

[请不要将本项目在国内宣传](https://github.com/wushuo894/ani-rss/discussions/504)

[从1.0升级至2.0的配置继承](https://github.com/wushuo894/ani-rss/discussions/427)


