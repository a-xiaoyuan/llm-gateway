# 路线

完整设计见 [docs/design.md](docs/design.md)。

## 功能

| # | 功能 | 设计要点 | 状态 |
| --- | --- | --- | --- |
| 1 | 非流式转发 + 模拟上游 | 连接 / 读取超时分开、错误透传、客户端 Key 不外传、可注入故障的假上游 | ✅ |
| 2 | 可观测：指标 + Prometheus + Grafana，基线压测 | 网关自身耗时单独统计、P99、控制标签基数 | ✅ |
| 3 | API Key 鉴权 | Key 只存 SHA-256、本地缓存、按 Key 配额度与可用模型 | ⏳ |
| 4 | 限流：按请求数和按 token；抽成组件 `kit-ratelimit`，项目拆成多模块 | Redis + Lua 令牌桶、token 预扣与结算、Redis 故障降级 | ⏳ |
| 5 | SSE 流式转发 | 客户端断开即取消上游、两块之间的空闲超时、流式用量结算 | ⏳ |
| 6 | 多上游：协议适配、加权负载、故障切换 + 熔断；抽成组件 `kit-llm-guard` | 适配器、可重试错误的边界、首字节后不重试、熔断状态机 | ⏳ |
| 7 | **Claude Code 接入**：Anthropic Messages 入口，双向翻译（含流式与工具调用）、模型映射；按开发者的用量面板 | 流式事件状态机、工具调用参数分块转发、直通与翻译两条路 | ⏳ |
| 8 | 用量记账：异步、不丢不重；抽成组件 `kit-reliable-consumer` | Redis Stream 消费组、至少一次 + 按请求 ID 幂等、死信 | ⏳ |
| 9 | 智能路由（按成本与延迟选模型） | 规则判断难度、价格 × 延迟打分、用固定请求集对比花费 | ⏳ |
| 10 | 工具调用网关：HTTP 接口按配置暴露为 MCP 工具；抽成组件 `kit-audit` | 按 Key 授权工具、高风险工具审批、审计与脱敏、限流 | ⏳ |
| 11 | 一个已有的 RAG 项目接入网关，并换用 `kit-llm-guard` | 组件跨项目复用，替换前后对比 | ⏳ |
| 12 | 最终压测、演示 | 数字写明机器与条件 | ⏳ |

只做设计、暂不实现：语义缓存、敏感信息脱敏（请求侧）。

## 技术选型

| 选择 | 理由 |
| --- | --- |
| Java 21 + Spring Boot 3.5 | 主流后端栈 |
| Spring MVC + 虚拟线程 | 网关大部分时间在等上游；虚拟线程让阻塞写法也能撑住并发，代码比响应式好读好调 |
| RestClient + JDK HttpClient | 调上游，JDK 自带，配合虚拟线程 |
| Redis（Lettuce）+ Lua + Stream | 限流原子扣减；记账队列 |
| MySQL + MyBatis-Plus + Flyway | 表少、SQL 简单；Flyway 管表结构版本 |
| Caffeine | Key 本地缓存 |
| Resilience4j | 熔断状态机用成熟库；重试判断、重试预算等大模型特有逻辑在 `kit-llm-guard` 里实现 |
| MCP Java SDK | 对外提供 MCP 服务 |
| Micrometer + Prometheus + Grafana | 指标与面板 |
| JUnit 5 + Testcontainers | 测试里用真实的 Redis、MySQL |
| Docker Compose、k6 | 一键起依赖；压测 |

**不用的：** WebFlux（理由见上）；Spring Cloud Gateway（数据面自己实现，便于做大模型特有的逻辑）；Kafka / RocketMQ（Redis Stream 足够，少一个中间件）。

## 不做

- 配置中心、配置发布（本项目关注数据面）
- RAG、聊天界面、桌面客户端
- 多机房、多活
