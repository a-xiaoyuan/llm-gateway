# llm-gateway

**大模型 API 网关：统一接入多家模型，提供 Key 鉴权、按请求和 token 限流、用量计费、多渠道故障切换与熔断，支持流式转发和可观测面板。**

Java 21 · Spring Boot 3.5 · 虚拟线程 · Redis · MySQL · Prometheus / Grafana

> 🚧 开发中。目前完成：非流式转发、模拟上游、可观测面板。进度见下方[路线](#路线)，完整设计见 [docs/design.md](docs/design.md)。

## 它解决什么问题

团队要给几十个开发者开通 AI 编程工具时，会遇到这些问题：

- 厂商密钥不能发给每个人，泄露了没法收回；
- 不知道每个人用了多少、花了多少钱；
- 有人用量失控，没法限制；
- 某家厂商出故障，所有人一起停工。

llm-gateway 站在开发者和模型厂商之间：

```
Claude Code / Codex / 业务程序 ──→ llm-gateway ──→ DeepSeek / 通义 / OpenAI 兼容服务 / Anthropic
       （每人一个网关 Key）          鉴权 · 限流 · 路由 · 故障切换 · 记账 · 指标
```

开发者只需把工具的接口地址指向网关（例如在 [CC Switch](https://github.com/farion1231/cc-switch) 里新增一个供应商），Key 换成自己的网关 Key。

## 功能

| 功能 | 说明 | 状态 |
| --- | --- | --- |
| 转发 | OpenAI 兼容接口原样转发；连接 / 读取超时分开；上游错误码透传（连不上 502、超时 504）；客户端 Key 不外传 | ✅ |
| 模拟上游 | 内置假模型服务，可注入 429 / 500 / 超时，压测和故障演练不花钱 | ✅ |
| 可观测 | 请求量、错误率、P50 / P95 / P99、网关自身耗时、按模型 token 用量，Grafana 面板；模型标签数量有上限，防止乱填撑爆 Prometheus（按人用量和花费随鉴权、记账加入） | ✅ |
| 鉴权 | 网关 Key 只存哈希，按 Key 配额度与可用模型 | ⏳ |
| 限流 | Redis + Lua 令牌桶，按请求数和按 token；token 先预扣、后按实际用量结算 | ⏳ |
| 流式 | SSE 透传，客户端断开即停止上游，流式用量结算 | ⏳ |
| 多上游 | 协议适配、多渠道加权负载、故障切换、熔断 | ⏳ |
| Claude Code 接入 | Anthropic Messages 接口，与 OpenAI 格式双向翻译（含流式与工具调用），模型映射 | ⏳ |
| 记账 | 异步写入，至少一次投递 + 按请求 ID 幂等，不丢不重 | ⏳ |
| 智能路由 | 按成本与延迟自动选模型 | ⏳ |
| 工具调用网关 | 把内部 HTTP 接口按配置暴露为 MCP 工具，带权限、审批、审计 | ⏳ |

通用能力会抽成可复用的 Spring Boot starter（限流、模型调用保护、可靠消费、审计），见[设计文档第 6 节](docs/design.md#6-共用组件库)。

## 快速开始

需要 JDK 21、Maven 3.9。

```sh
mvn -q package -DskipTests
```

终端 1，启动模拟上游（端口 9090）：

```sh
java -jar mock-upstream/target/mock-upstream-0.1.0-SNAPSHOT.jar
```

终端 2，启动网关（端口 8787）：

```sh
java -jar gateway/target/llm-gateway-0.1.0-SNAPSHOT.jar
```

终端 3，发一个请求：

```sh
curl -s http://localhost:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "demo", "messages": [{"role": "user", "content": "hi"}]}'
```

把 `model` 换成 `mock-429`、`mock-500`、`mock-slow` 可以模拟上游故障。

**接真实模型：** 启动网关前设置环境变量 `UPSTREAM_BASE_URL`（到 `/v1` 为止）和 `UPSTREAM_API_KEY`。

**看面板**（需要 Docker）：

```sh
docker compose -f deploy/docker-compose.yml up -d
```

打开 http://localhost:3000 就是网关面板（匿名只读）。Prometheus 在 http://localhost:9091 ，指标原文在 http://localhost:8787/actuator/prometheus 。

## 性能基线

网关 + 模拟上游（固定 200ms 延迟），200 个并发用户连续请求 60 秒，[k6](https://k6.io) 脚本见 [bench/chat-baseline.js](bench/chat-baseline.js)。

| | 吞吐 | 失败率 | P50 | P95 | P99 |
| --- | --- | --- | --- | --- | --- |
| 直连模拟上游（对照） | 950 req/s | 0 | 214.3 ms | 217.3 ms | 219.0 ms |
| 经过网关 | 918 req/s | 0 | 216.7 ms | 223.9 ms | 246.8 ms |

- 网关代码自身耗时（解析模型名、记指标、组装响应）P99 < 0.05 ms，低于直方图最小桶。
- 端到端多出的延迟（P50 +2.4 ms，P99 +28 ms）主要在网关到上游的那一跳：网关测到的上游耗时 P99 是 242 ms，直连是 219 ms。
- 条件：AMD Ryzen 7 8745HS（8 核 16 线程）、36 GB 内存、Windows 11；k6、网关、模拟上游跑在同一台机器上，互相抢 CPU，数字只用于前后对比。
- Tomcat 默认参数下同样的压测有 0.2% 的请求连不上（每条连接 100 个请求后断开 + 等待队列只有 100），调大 `max-keep-alive-requests` 和 `accept-count` 后为 0。

## 项目结构

```
gateway/          网关本体，按功能分包（proxy、observe、common，后续 auth、ratelimit、routing ……）
mock-upstream/    模拟上游：OpenAI 格式的假模型服务，可注入故障
deploy/           Prometheus + Grafana（docker compose），面板随容器自动加载
bench/            k6 压测脚本
docs/             设计文档
```

## 测试

```sh
mvn test
```

测试里用 JDK 自带的 `HttpServer` 起一个可控的假上游，覆盖正常转发、错误透传、超时、上游不可用和密钥不外传；指标测试直接读 `/actuator/prometheus`，检查请求数、token、直方图和模型标签上限。

## 路线

见 [ROADMAP.md](ROADMAP.md)。

## 许可证

[Apache-2.0](LICENSE)
