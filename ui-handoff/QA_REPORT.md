# 0.27 交接包与真机验证

本轮原生安装包已构建并验证签名，0.26 功能与 0.27 固定背景真机结果见 [真机验收记录](真机验收-0.27.md)。

离线骨架沿用同一结构的 56 个页面／状态验证；0.27 同步版本、源码及固定背景契约。通过项目：

- PASS offline file:// load with external HTTP blocked
- PASS all 56 page/state combinations render
- PASS capture touch targets at four viewport sizes
- PASS connected idle, disabled capture and cover open/close with macro orientation retained
- PASS simulated macro freeze/resume and rotation controls
- PASS help current/candidate text and contextual ear options
- PASS current Settings order and photo paging commit, cancellation and first-edge resistance
- PASS markup simulation and busy state
- PASS local review persistence and JSON export/import round trip
- PASS theme import and rejection of unsafe/invalid inputs

电量估算、照片松手判定与硬件统计的本地回归通过。浏览器骨架不等同于 Android 真机，未测试项目以真机记录中的范围为准。
