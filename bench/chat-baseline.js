// 基线压测：网关 + 模拟上游（固定 200ms 延迟），看网关自己加了多少延迟、能扛多少并发。
//
//   k6 run bench/chat-baseline.js
//   k6 run -e VUS=400 -e DURATION=60s bench/chat-baseline.js
//
// 每个虚拟用户发完一个请求、拿到响应后马上发下一个。上游固定 200ms，
// 所以理想吞吐 ≈ VUS / 0.2s；实际吞吐离这个数越近，说明网关越没有拖后腿。
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.GATEWAY_URL || 'http://localhost:8787';

export const options = {
  vus: Number(__ENV.VUS || 200),
  duration: __ENV.DURATION || '60s',
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    http_req_failed: ['rate<0.001'],
  },
};

const body = JSON.stringify({
  model: 'qwen-plus',
  messages: [{ role: 'user', content: '用一句话介绍一下你自己' }],
});
const params = { headers: { 'Content-Type': 'application/json' } };

export default function () {
  const res = http.post(`${BASE}/v1/chat/completions`, body, params);
  check(res, { 'status 200': (r) => r.status === 200 });
}
