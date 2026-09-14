# Kế hoạch benchmark kotrace

Trạng thái: hoàn tất giai đoạn 3 và hai follow-up experiment; giữ report-index v2, loại event-buffer.
Ngày lập kế hoạch: 2026-09-13. Run sạch và phân tích hoàn tất ngày 2026-09-14.

Kết quả chính: [`benchmarks/results/20260914-sol-high-stage2b/analysis.md`](../benchmarks/results/20260914-sol-high-stage2b/analysis.md).
Follow-up: [`benchmarks/results/20260914-report-index-candidate-v2/analysis.md`](../benchmarks/results/20260914-report-index-candidate-v2/analysis.md).
Event-buffer: [`benchmarks/results/20260914-event-buffer-candidate/analysis.md`](../benchmarks/results/20260914-event-buffer-candidate/analysis.md).
Run `20260913-sol-high-stage2` được giữ làm lịch sử nhưng không dùng làm baseline chính vì dữ liệu đi qua
hai snapshot source trong lúc ADR-015/B01 được triển khai. Run hiệu chỉnh `stage2b` giữ một snapshot xuyên suốt.

## Các điểm dừng đã thống nhất

1. Thiết kế workload, baseline, phương pháp kiểm chứng → dừng để duyệt tài liệu này.
2. Tích hợp module benchmark, kiểm tra correctness, chạy smoke và baseline đầy đủ → dừng để duyệt dữ liệu thô và chất lượng lần chạy.
3. Phân tích số liệu, đối chiếu giả thuyết, đề xuất ưu tiên → dừng để duyệt kết luận.
4. Chỉ khi giai đoạn 3 còn điểm chưa giải thích: thiết kế và chạy phép đo bổ sung sau xác nhận.

Sửa thuật toán core, áp dụng ADR-015, commit và publish không thuộc kế hoạch này.
Model đề xuất: GPT-6 Astra; effort xhigh cho thiết kế/phân tích, high cho tích hợp/chạy.
Đây là cấu hình đề xuất, không phải xác nhận đã đổi model/effort của task.

## Mục tiêu và hiện trạng

Đo độ trễ, allocation và xu hướng tăng chi phí của tracing trong một flow; xác định ảnh hưởng
của số event, hình dạng cây, số adapter và cạnh tranh giữa coroutine. Phạm vi là JVM core.
Không suy rộng kết quả macOS HotSpot thành hiệu năng Android ART, OkHttp network hay Room database.

Mốc khảo sát: `40cf20a87d5d5c49f4c1d029f07d6ac555cc3ec4`, version `0.4.0`.
Kotlin `2.4.10`, coroutines `1.11.0`, Gradle `9.3.1`, target bytecode JVM 11.
Shell hiện dùng Oracle JDK 17.0.10 arm64; máy có Oracle JDK 21.0.3 arm64;
Gradle daemon khai báo JetBrains JDK 21. JVM chạy benchmark phải được chọn tường minh và ghi lại,
không mặc định suy ra từ JVM của shell hoặc Gradle daemon.

Working tree có thay đổi ở `DECISIONS.md`, `decisions/adr-015-exception-origin-token.md` và `docs/`.
Lưu commit, diff và hash nguồn thực tế khi chạy; kiểm tra lại trước/sau đo để không trộn hai phiên bản.
ADR-015 hiện Proposed, code report vẫn dedup theo topology. Không benchmark như thể ADR đã được triển khai.

## Tích hợp dự kiến

Module riêng `:benchmarks`, không publish, phụ thuộc core bằng `project(":")`.
Dùng Kotlin cho workload gọi suspend API; dùng Java JMH harness và annotation processor chính thức
để tránh thêm compiler plugin cho benchmark Kotlin. JMH runtime và generator phải cùng phiên bản,
được pin sau khi kiểm tra tương thích ở giai đoạn 2. Chạy bằng runner độc lập trong JVM fork,
không đo trong JUnit hoặc IDE. Harness sinh tự động để trong build output.

Các file dự kiến: `benchmarks/build.gradle.kts`, Java harness, Kotlin workload/helpers,
correctness tests, hướng dẫn chạy; thêm module trong settings và version JMH trong catalog.
Không đưa JMH vào dependencies của core hay BOM. Kiểm tra build toàn repo sau tích hợp.

## Ma trận đo có giới hạn

Mỗi nhóm thay đổi một hoặc hai chiều liên quan; không nhân Cartesian toàn bộ tham số.
Một operation là một flow hoặc batch ghi N event như định nghĩa dưới đây.

| Nhóm | Workload / tham số | Baseline và phạm vi đo |
|---|---|---|
| A · lifecycle | operation thuần; coroutine thuần; auto-root không config; auto-root config rỗng; auto-root một live hoặc một report sink | Cùng payload và cùng ranh giới runBlocking cho các biến thể coroutine; không lấy chênh lệch với code thuần làm riêng chi phí tracing |
| B · capture | Span mới + log batch N = 0, 10, 100, 1.000; không sink hoặc một live sink reject-all | Tạo Span và append đều trong phép đo; không để buffer tăng qua invocation. N=0 là baseline tạo Span; báo ns/batch và bytes/batch |
| C · message và event kind | 100 event; Log static hoặc message format từ dữ liệu runtime; Named; Exception ghi trực tiếp | So sánh live accept/reject; exception allocation tách khỏi chi phí ghi bằng throwable fixture có sẵn, ghi rõ đây không phải throw/catch |
| D · live fan-out | 100 log; 1, 4, 16 adapter; accept-all, reject-all, mixed; sensitive bị chặn/được nhận | Cùng sink tiêu thụ tối thiểu. Có live-only, report-only và live+report ở một workload cố định để đo tổng hai đường |
| E · report scaling | Cây chain/star/balanced, tổng span = 1, 16, 128, 512; một log mỗi span; một report sink | Báo riêng report trên fixture đã dựng và toàn flow dựng+capture+report; không cộng/trừ hai số rồi coi là phân rã chính xác |
| F · outcome/policy | Trace cố định 128 span; OK bị bỏ trước iteration; OK/ERROR/CANCELLED được consume; 1, 4, 16 report adapter | Cho thấy giá walk ngay cả khi bỏ outcome; event gate và sensitive gate dùng cùng dữ liệu |
| G · failure | Một lỗi climb qua depth 1, 8, 32; hai nhánh độc lập; recover A rồi throw B | Có baseline throw/catch coroutine; lỗi mới cho mỗi flow. Recovery on/off ở JVM fork riêng; B01 là correctness failure đã biết, không công nhận số của case mất B là kết quả hợp lệ |
| H · concurrency | 1, 4, 16 worker coroutine; mỗi worker 100 log; shared-span so với child-span-per-worker | Dispatcher cố định 4 thread tạo ngoài vùng đo; một JMH driver; baseline cùng scheduling không tracing; chờ mọi child hoàn thành |
| I · sink latency | Một live sink nhanh hoặc chặn khoảng 1 ms/record; 1 và 10 record; đối chiếu report sink | Dùng delay giả đồng bộ có ghi rõ scheduler jitter; đo thời gian ứng dụng phải chờ, không coi đây là hiệu năng vendor sink |
| J · retained memory | Giữ một trace mở với N = 0, 100, 1.000, 10.000 event, payload cố định | Chạy chẩn đoán heap riêng: sau GC tại checkpoint, xem retained graph/dominator của collector; thả reference và đối chiếu. Không dùng số allocation JMH thay cho retained heap |

Fixture report E/F không đổi sau khi dựng. Nếu reuse fixture, lazy message phải được warm trước
và gắn nhãn **report/warm-message**. Toàn flow với event mới mỗi invocation đo thêm lần resolve message đầu tiên.
Không dùng `@Setup(Level.Invocation)` để che chi phí reset cho phép đo nano; sample JMH cảnh báo
overhead của kiểu setup này. Cây lớn đo trong report fixture; không cần tạo hàng trăm coroutine frame
để đo riêng thuật toán walk. Workload end-to-end dùng API span thật và ghi rõ chi phí ID generation.

Span-less: thêm đối chiếu `emitLog` reject/accept ở nhóm D với cùng config và cùng payload.
Config override là mặc định để cô lập workload. Một đối chiếu global config dùng JVM fork riêng,
install một lần ở trial; không gọi resetForTest trong vùng đo.

## Kiểm chứng trước khi đo

- Harness tiêu thụ kết quả qua giá trị trả về/Blackhole; payload lấy từ state runtime để hạn chế constant folding.
- Fake sink không giữ vô hạn records; consume fields/message, count/checksum với cùng cách làm giữa các biến thể.
  Dùng consumer thread-safe khi workload thực sự concurrent; ghi nhận overhead của consumer bằng baseline.
- Correctness preflight chạy cùng cấu hình JVM với benchmark: kiểm tra return value, record count/kind,
  policy reject, sensitive gate, named live-only, exception và outcome, đủ children đã join.
- Kiểm tra lazy message không được gọi khi mọi sink từ chối; với đường tuần tự, nhiều sink không làm
  build lại một message đã resolve. Không áp đặt invariant gọi đúng một lần cho provider concurrent
  dùng LazyThreadSafetyMode.PUBLICATION. Assert nằm ngoài vùng timed.
- Chứng minh workload thật sự capture/consume số event đã khai báo; report-only không được vô tình
  không có collector. Với shared-span concurrent, có assertion thread/context và tổng event.
- B01 tái hiện riêng, lưu expected/actual rõ ràng. Nó không chặn benchmark đường không bị ảnh hưởng,
  nhưng kết quả failure phải ghi phạm vi correctness và không dùng lỗi mất record để kết luận nhanh hơn.
- Fault hook thu lỗi ngoài timed assertions và được kiểm tra sau batch/trial; lỗi bị isolation nuốt
  không được biến một benchmark hỏng thành số liệu có vẻ hợp lệ.

## Protocol chạy giai đoạn 2

Chọn Oracle JDK 21.0.3 đã có trên máy làm runtime baseline; pin đường dẫn JVM khi gọi runner.
Ghi CPU model, core count, RAM, OS, vendor/version JVM, GC, heap, coroutines debug/recovery flags,
JMH version, commit/diff, lệnh chạy, tham số và thời gian. Heap dự kiến `-Xms1g -Xmx1g`, G1GC.
Không thay đổi flags giữa hai đối chiếu; startup/cold JVM không nằm trong baseline steady-state.

1. Compile harness và correctness preflight; core tests và build toàn repo.
2. Smoke tất cả case hợp lệ: 1 fork, 1 warm-up × 1 s, 1 measurement × 1 s. Chỉ kiểm tra harness.
3. Baseline chính: AverageTime, 3 fork, 5 warm-up × 1 s, 5 measurement × 1 s, một JMH thread
   trừ case có concurrency được khai báo. Allocation/GC dùng `-prof gc`; có lượt đối chiếu không profiler
   trên case đại diện để nhận diện overhead của profiler.
4. Nhóm H/I chạy SampleTime riêng để có p50/p95/p99 thời gian hoàn thành **flow trong harness**;
   không gọi chúng là production latency dưới tải open-loop. Throughput đo bằng mode riêng trên case H đại diện.
5. Nhóm J chạy ngoài timing suite; nếu công cụ heap không khả dụng, ghi rõ chưa có retained-memory result.

Trước full run, runner liệt kê chính xác số case và thời lượng ước tính từ số fork/iteration.
100 case theo profile chính có khoảng 50 phút iteration, chưa tính JVM startup và lượt bổ sung.
Chạy nối tiếp; không chạy build, test hay suite khác cạnh tranh CPU trong thời gian đo.
Nếu variance/drift khiến chưa kết luận được, lưu dữ liệu và đề nghị phép đo bổ sung ở điểm dừng,
không tự mở rộng thành chạy vô hạn. Không dùng System.gc() trong timed invocation.

Kết quả giai đoạn 2: source harness, commands tái lập, JSON thô từng fork/iteration khi runner hỗ trợ,
console logs, metadata và correctness report. Lưu bản cần bàn giao trong thư mục kết quả có run ID;
build output giữ theo quy ước Gradle. Không commit tự động các file kết quả/heap dump lớn.

## Phân tích giai đoạn 3 và tiêu chí kết luận

- Báo đơn vị rõ: ns/flow, ns/batch, bytes/flow hoặc bytes/batch; ns/event quy đổi chỉ cho N > 0
  và gắn nhãn có bao gồm chi phí cố định. Hiển thị score, error/confidence và phân tán giữa fork.
- So sánh cùng payload, JVM, tree size và sink; chênh lệch baseline là ước lượng, chịu ảnh hưởng JIT/GC.
- Vẽ chi phí theo N và theo adapter count để kiểm tra giả thuyết copy-on-write, quét cây lặp lại,
  shared record construction và outcome skip. Không gọi tương quan là bằng chứng nguyên nhân;
  chỉ đề xuất profiling bổ sung khi cần giải thích.
- Phân biệt allocation rate với live/retained heap; latency sink giả với core overhead;
  wall time toàn flow với CPU time; report tree order với global chronological order.
- Không đặt ngưỡng pass/fail hiệu năng tùy ý khi chưa có ngân sách workload thực tế.
  Kết luận phải nêu vùng dữ liệu đã đo, bất định, correctness exclusions và ưu tiên có bằng chứng.

## Nguồn phương pháp

- [OpenJDK JMH — dead-code elimination](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java): consume kết quả tính toán để tránh đo code đã bị loại.
- [OpenJDK JMH — per-invocation setup](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_38_PerInvokeSetup.java): state thay đổi cần reset đúng và tránh overhead setup làm sai phép đo.
- Nguồn repo: `Trace.kt`, `Report.kt`, `Span.kt`, `SpanCollector.kt`, `event/Emit.kt`, `Kotrace.kt`, ADR-013/014/015.
