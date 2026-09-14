# Phân tích benchmark kotrace — giai đoạn 3

Trạng thái: **hoàn tất**. Ngày 2026-09-14.

Follow-up đã hoàn tất: [report tree index candidate v2](../20260914-report-index-candidate-v2/analysis.md)
giảm latency 512-span khoảng 11,2–12,9× và giữ nguyên correctness. Ưu tiên pre-index report dưới đây
được xem là đã xử lý. [Event-buffer candidate](../20260914-event-buffer-candidate/analysis.md) cũng đã được
đo và loại vì regression trên trace 0–10 event, read path và report path.

Nguồn chính là run sạch `20260914-sol-high-stage2b` trên Oracle JDK 21.0.3, Apple M1 Pro,
G1GC, heap cố định 1 GiB. Suite chính có 78/78 case, 3 fork × 5 warm-up × 5 measurement,
mỗi iteration 1 giây. Dấu `±` dưới đây là `scoreError` do JMH báo (confidence 99,9%).

Run `20260913-sol-high-stage2` đã thực thi đủ suite chính nhưng không được dùng làm baseline chính:
file kết quả đóng trong lúc ADR-015/B01 đang được sửa, còn các lượt bổ sung chạy sau khi class mới đã
được compile. `stage2b` giữ nguyên một snapshot source xuyên suốt và là nguồn cho mọi kết luận dưới đây.

## Kết luận điều hành

1. **Append event là hotspot lớn nhất khi một span có nhiều event hoặc bị nhiều coroutine ghi chung.**
   Với đường không sink, tăng từ 100 lên 1.000 event làm thời gian tăng 22,82× và allocation tăng
   71,11×, dù số event chỉ tăng 10×. Điều này phù hợp với chi phí copy mảng của
   `CopyOnWriteArrayList` sau mỗi append. Ở 16 worker, shared-span chậm 4,00× và allocate 9,38×
   child-span-per-worker.
2. **Report walk tăng vượt tuyến tính theo số span.** Từ 16 lên 512 span (32×), thời gian tăng
   285–557× tùy shape, trong khi allocation tăng khoảng 36–39×. Source cho thấy mỗi node gọi
   `childrenOf`, quét và sort từ toàn bộ danh sách; đây là nguyên nhân trực tiếp có thể kiểm chứng cho
   xu hướng gần bậc hai.
3. **Gate/fan-out đang có một đặc tính tốt:** reject-all giữ chi phí gần như phẳng khi số adapter tăng;
   accept-all từ 1 lên 16 adapter tăng khoảng 4× chứ không 16×, phù hợp với việc record được dựng một
   lần rồi phân phối cho các adapter nhận.
4. **Live sink là synchronous backpressure.** Sink giả chặn 1 ms tạo median 1,28 ms cho 1 event và
   12,73 ms cho 10 event; jitter của scheduler làm p99 lần lượt lên 5,42 và 22,09 ms. Đây là thời gian
   ứng dụng bị giữ trong callback, không phải core overhead hay latency của vendor thật.
5. **ADR-015 sửa correctness mà chưa cho thấy regression latency rõ ràng ở failure climb.** So với run
   cũ, score thay đổi từ −2,3% đến +3,4% và các interval chồng lấp; allocation của traced failure tăng
   khoảng 4,4–6,9%. Case depth 8 của run mới nhiễu cao nên không dùng để khẳng định mức chênh chính xác.

Không có ngân sách production nên báo cáo không gắn nhãn pass/fail tuyệt đối. Các ưu tiên dựa trên độ dốc,
allocation và cấu trúc thuật toán, không dựa vào một ngưỡng tùy ý.

## 1. Lifecycle một trace rỗng

| Workload | Score | Allocation |
|---|---:|---:|
| `runBlocking` baseline | 84,3 ± 1,5 ns/flow | 208 B/flow |
| `withContext(config)` baseline | 401,9 ± 51,5 ns/flow | 512 B/flow |
| auto-root, không config | 8,14 ± 0,41 µs/flow | 14.843 B/flow |
| auto-root, config rỗng | 8,32 ± 0,46 µs/flow | 15.496 B/flow |
| auto-root, 1 live adapter | 8,42 ± 0,22 µs/flow | 15.304 B/flow |
| auto-root, report adapter không consume | 9,58 ± 4,21 µs/flow | 15.504 B/flow |

So với baseline coroutine tương ứng, một auto-root rỗng thêm khoảng 7,9–8,1 µs và gần 15 KiB
allocation. Case report-skip có fork CV 22% nên chỉ đủ để nói cùng bậc độ lớn, không đủ để xếp hạng
với các biến thể auto-root khác.

## 2. Capture và số event

Đường `NONE` bao gồm tạo `Span` mới và append toàn batch, không có sink:

| Event/batch | Score | Quy đổi có fixed cost | Allocation |
|---:|---:|---:|---:|
| 0 | 18,1 ± 0,5 ns | — | 224 B/batch |
| 10 | 348,7 ± 3,4 ns | 34,9 ns/event | 1.344 B/batch |
| 100 | 3,69 ± 0,04 µs | 36,9 ns/event | 29.424 B/batch |
| 1.000 | 84,15 ± 1,18 µs | 84,1 ns/event | 2.092.225 B/batch |

Từ 100 → 1.000 event, chi phí tăng nhanh hơn số lượng: 22,82× latency và 71,11× allocation.
`Span.events` là `CopyOnWriteArrayList`; tổng lượng reference phải copy sau N append tăng theo tổng
`0 + 1 + … + N-1`, nên kết quả này là bằng chứng thực nghiệm phù hợp với O(N²), không chỉ là tương quan
không có cơ sở source.

Với 100 event, `LIVE_REJECT` là 4,04 ± 0,26 µs và 31.024 B/batch; `LIVE_ACCEPT` là
6,43 ± 0,39 µs và 51.056 B/batch. Reject tránh resolve message/dựng record; accept thêm khoảng
2,40 µs và 20 KiB cho 100 lần delivery. Lượt không GC profiler của chính case accept là
6,74 ± 0,54 µs, cao hơn 4,7% nhưng interval chồng lấp; không thấy profiler làm phình score một cách rõ ràng.

## 3. Live fan-out, event kind và sensitive gate

| 100 log | 1 adapter | 4 adapters | 16 adapters |
|---|---:|---:|---:|
| reject-all | 4,06 ± 0,25 µs | 3,93 ± 0,03 µs | 4,51 ± 0,25 µs |
| accept-all | 6,45 ± 0,36 µs | 8,69 ± 0,04 µs | 25,72 ± 2,12 µs |

Reject 1 → 16 chỉ tăng 1,11×; accept tăng 3,99×. Đây là dấu hiệu tốt của cơ chế shared record
construction: policy vẫn phải chạy trên từng adapter, nhưng payload chỉ được materialize một lần cho
tập adapter chấp nhận.

Ba kind `LOG`, `NAMED`, `EXCEPTION` nằm cùng bậc độ lớn (accept: 6,27–6,95 µs/100 event), và một số
interval chồng lấp; không có đủ bằng chứng để tối ưu theo kind. Sensitive event bị policy mặc định chặn
là 4,23 ± 0,06 µs/100 event; cho phép delivery là 6,58 ± 0,45 µs và thêm khoảng 19,2 KiB allocation.

## 4. Report scaling và outcome

Fixture có một log đã warm trên mỗi span; score chỉ đo `reportTrace`, không đo dựng cây/capture lần đầu.

| Shape | 16 spans | 128 spans | 512 spans | Tăng 16 → 512 |
|---|---:|---:|---:|---:|
| chain | 1,36 ± 0,07 µs | 41,28 ± 11,49 µs | 758,49 ± 77,30 µs | 557× |
| star | 1,66 ± 0,16 µs | 35,96 ± 1,35 µs | 473,43 ± 15,11 µs | 285× |
| balanced | 1,65 ± 0,13 µs | 41,53 ± 6,56 µs | 528,39 ± 15,18 µs | 321× |

Allocation 16 → 512 chỉ tăng 35,8–38,6×, gần với 32× số span. CPU mới là vấn đề nổi bật.
`childrenOf(parent)` hiện filter toàn bộ `all` rồi sort cho mỗi node. Với exception-bearing tree,
`birthplaceExceptionsAmong(all)` còn dựng `byId` và quét descendant lặp lại; fixture report này không có
exception nên chưa đo phần chi phí bổ sung đó.

Outcome suite 128-span cho kết quả trung bình qua ba status:

| Adapter behavior | 1 adapter | 4 adapters | 16 adapters |
|---|---:|---:|---:|
| trả về, không consume sequence | 37,99 µs | 37,41 µs | 38,61 µs |
| consume toàn bộ sequence | 39,34 µs | 43,18 µs | 61,51 µs |

Điều này xác nhận tree walk/`WalkEntry` được dựng một lần trước adapter; adapter trả về sớm tránh
record conversion nhưng **không tránh tree walk**, khoảng 35 KiB/batch trong fixture này.

Giới hạn quan trọng: harness `REPORT_SKIP` hiện trả về cho cả `OK`, `ERROR`, `CANCELLED`, còn
`REPORT_CONSUME` consume cả ba. Tham số status chỉ đổi verdict truyền vào adapter, chưa mô phỏng đúng
“chỉ skip OK, consume ERROR/CANCELLED”. Vì vậy suite này chứng minh consumer-vs-non-consumer và shared
walk, nhưng không đủ để kết luận policy theo outcome.

## 5. Failure climb và ADR-015

| Depth | Coroutine throw baseline | Traced failure | Allocation traced |
|---:|---:|---:|---:|
| 1 | 1,04 ± 0,28 µs | 10,56 ± 0,33 µs | 17.683 B/flow |
| 8 | 5,21 ± 0,17 µs | 38,61 ± 10,17 µs | 63.435 B/flow |
| 32 | 21,62 ± 0,43 µs | 120,14 ± 3,69 µs | 216.648 B/flow |

Từ depth 8 → 32, traced failure thêm trung bình khoảng 3,40 µs/level, trong đó baseline coroutine
throw thêm khoảng 0,68 µs/level. Đây là full propagation cost: span lifecycle, status/event record,
coroutine boundary và lineage handling; không được diễn giải thành riêng chi phí `lineageKeyOf`.

So với dữ liệu cũ trước khi ADR-015 ổn định, traced latency depth 1/8/32 đổi +3,4%/+1,4%/−2,3%,
đều nhỏ hơn uncertainty giữa các run. Allocation tăng +4,4%/+6,9%/+5,4%. Kết luận hợp lệ là
“chưa thấy latency regression đo được, có allocation overhead khoảng 5%”, không phải “zero-cost”.

## 6. Concurrency

Mỗi worker ghi 100 log; dispatcher cố định 4 thread.

| Workers | Scheduling baseline | Shared span | Child span/worker |
|---:|---:|---:|---:|
| 1 | 7,04 ± 0,19 µs | 17,07 ± 0,79 µs | 26,98 ± 1,31 µs |
| 4 | 8,90 ± 0,68 µs | 64,79 ± 3,55 µs | 48,01 ± 1,50 µs |
| 16 | 12,62 ± 0,22 µs | 544,70 ± 19,09 µs | 136,00 ± 10,76 µs |

Khi worker tăng 16×, shared-span latency tăng 31,91× và allocation tăng 99,63×; child-span tăng
5,04× latency và 11,24× allocation. Ở 16 worker, shared span allocate 5,61 MB/flow so với 0,60 MB/flow
cho child spans. Đây là signature của append contention kết hợp copy-on-write, không phải chỉ scheduler.

SampleTime cho hotspot shared/16: mean 554,80 ± 3,51 µs, p50 529,41 µs, p95 666,52 µs,
p99 1.329,93 µs. Throughput là 0,001764 ops/µs ≈ 1.764 flow/s; mỗi flow có 1.600 event,
tương đương khoảng 2,82 triệu event/s trong closed-loop harness này. Không suy rộng thành production
throughput dưới open-loop load.

## 7. Synchronous sink latency

| Events | Mean | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|
| 1 | 1,49 ms | 1,28 ms | 3,01 ms | 5,42 ms |
| 10 | 14,01 ms | 12,73 ms | 18,45 ms | 22,09 ms |

`LockSupport.parkNanos(1_000_000)` chỉ là sink giả; scheduler jitter giải thích tail cao. Kết quả vẫn
chứng minh cơ chế: `onLive` được gọi đồng bộ và latency sink nằm trực tiếp trên critical path của ứng dụng.

## Ưu tiên đề xuất

1. **P1 — đã đánh giá và loại locked event-buffer candidate.** Candidate cải thiện mạnh ở `N=100/1.000`
   và shared 4/16 workers, nhưng làm trace 0 event, read path và report path xấu đi. Giữ
   `CopyOnWriteArrayList`; không thử candidate khác nếu chưa có workload production chứng minh span nhiều
   event là trường hợp ưu tiên.
2. **P1 — hoàn tất: pre-index report tree một lần.** Candidate v2 dựng adjacency đã sort trước walk và
   tính birthplace lineage post-order; paired benchmark cho speedup 11,2–12,9× ở 512 span.
3. **P2 — giữ synchronous adapter contract nhưng làm backpressure hiển nhiên.** KDoc/sample nên khuyến nghị
   adapter tự enqueue sang bounded worker nếu sink có I/O; library không nên âm thầm đổi semantics sang async.
4. **P2 — sửa outcome benchmark trước khi dùng nó cho quyết định policy.** Adapter phải gate theo status thật;
   thêm failure-heavy report fixture để đo riêng chi phí ADR-015 birthplace walk.
5. **P3 — bổ sung retained-heap probe.** Hiện chỉ có allocation/operation; chưa biết retained graph của trace
   mở 10.000 event nên không được dùng các số B/op trên làm memory-retention claim.

## Điểm chưa giải thích và ngưỡng dừng

Các hotspot P1 đã có cả bằng chứng số và đường code tương ứng; không cần tự động mở giai đoạn 4 để xác nhận
lại cùng một điều. Hai khoảng trống cần phép đo mới nếu muốn trả lời là retained heap và report tree nhiều
exception. Một stage 4 hợp lý chỉ nên nhắm đúng hai điểm đó (sau xác nhận), cùng outcome gate đã sửa; không
cần lặp lại toàn bộ 78-case suite.
