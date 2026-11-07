# Bộ benchmark kotrace

Đo độ trễ, allocation và cách chi phí tracing tăng theo số event, hình dạng cây, số adapter và cạnh tranh
giữa coroutine. Phạm vi là JVM core trên HotSpot: kết quả không suy rộng thành hiệu năng Android ART, OkHttp
network hay Room database. Số liệu hiện hành nằm trong [README](../README.md#performance) và run gần nhất
trong [`benchmarks/results/20261102-fixed`](../benchmarks/results/20261102-fixed/analysis.md).

## Cấu trúc

Module `:benchmarks` không publish, phụ thuộc core bằng `project(":")`; JMH không vào dependency của core hay
BOM. Harness viết bằng Java với annotation processor chính thức của JMH, workload gọi API suspend viết bằng
Kotlin trong `BenchmarkSupport.kt`. Benchmark chạy trong JVM fork riêng qua `BenchmarkMain`, không trong JUnit
hay IDE. `BenchmarkPreflightTest` kiểm tra workload trước khi đo và chạy trong `./gradlew build`.

## Workload

Mỗi nhóm thay đổi một hoặc hai chiều, không nhân Cartesian toàn bộ tham số. Một operation là một flow, hoặc
một batch ghi N event, như định nghĩa trong từng benchmark.

| Benchmark | Đo gì | Tham số |
|---|---|---|
| `LifecycleBenchmark` | operation thuần, coroutine thuần, coroutine có configuration, auto-root không config, config rỗng, một live adapter, một report adapter bỏ qua outcome | không |
| `CaptureBenchmark` | tạo span và ghi N log | N = 0, 10, 100, 1.000; không sink, live từ chối, live nhận |
| `EventKindBenchmark` | 100 event theo loại | log, named, exception; live từ chối hoặc nhận |
| `EventCapBenchmark` | 2.000 log vào một span có cap | cap 100, 1.000, 10.000 (không loại event) |
| `EventReadBenchmark` | duyệt snapshot timeline | 10, 100, 1.000 event |
| `LiveFanoutBenchmark` | 100 log fan-out live | 1, 4, 16 adapter; từ chối hoặc nhận |
| `SensitiveRoutingBenchmark` | 100 log `sensitive` | adapter từ chối hoặc nhận `sensitive` |
| `ReportScalingBenchmark` | report cây dựng sẵn, message đã warm | chain, star, balanced; 1, 16, 128, 512 span |
| `ReportFailureScalingBenchmark` | report cây có một birthplace exception ở mỗi child | 16, 128, 512 span |
| `ReportOutcomeBenchmark` | report cây 128 span theo outcome | bỏ qua hoặc đọc; OK, ERROR, CANCELLED; 1, 4, 16 adapter |
| `CrashSinkBenchmark` | report cây cân bằng 128 span có một birthplace đã được phục hồi; đọc một lần, đếm breadcrumb rồi chọn exception theo outcome và origin | 0, 1, 10 lỗi qua `attachException`; OK, ERROR; một crash adapter hoặc thêm 3 adapter bỏ qua report không phải ERROR (12 trường hợp) |
| `FailureBenchmark` | lỗi leo qua chuỗi span so với throw/catch coroutine thuần | độ sâu 1, 8, 32 |
| `ConcurrencyBenchmark` | 100 log mỗi worker trên dispatcher 4 thread | worker 1, 4, 16; không tracing, span chung dưới cap, span chung với cap mặc định, mỗi worker một child span |
| `SinkLatencyBenchmark` | thời gian ứng dụng chờ một live sink chặn 1 ms mỗi record (SampleTime) | 1, 10 record |

Report trên fixture dựng sẵn được gắn nhãn warm-message: lazy message đã resolve ở lần report đầu. Retained
heap (bộ nhớ một trace mở giữ lại) chưa được đo; số allocation của JMH không thay cho nó.

## Quy tắc harness

- Kết quả được tiêu thụ qua giá trị trả về; payload lấy từ state runtime để tránh constant folding.
- Sink giả không giữ records vô hạn: đếm và cộng checksum theo cùng cách giữa các biến thể, thread-safe khi
  workload thật sự concurrent.
- Không dùng `@Setup(Level.Invocation)` cho phép đo nano; trạng thái thay đổi được dựng mới trong operation.
- Preflight kiểm tra ngoài vùng timed: số record và loại, policy từ chối, cổng `sensitive`, named chỉ live,
  exception và outcome, đủ child đã join, cap giữ đúng số event và đếm mọi event bị loại.

## Cách chạy

Profile chuẩn: Oracle JDK 21 do toolchain chọn, G1GC, heap `-Xms1g -Xmx1g`, AverageTime, 3 fork, 5 warm-up ×
1 giây, 5 measurement × 1 giây, `-prof gc`. Không chạy build, test hay suite khác cạnh tranh CPU trong lúc đo.

```text
./gradlew :benchmarks:test
./gradlew :benchmarks:jmh -PjmhArgs="<regex> -f 3 -wi 5 -i 5 -w 1s -r 1s -prof gc -rf json -rff <file>.json -o <file>.log"
```

Smoke kiểm tra harness: `-f 1 -wi 0 -i 1 -r 100ms`. `SinkLatencyBenchmark` cho p50/p95/p99 thời gian hoàn
thành flow trong harness, không phải latency production dưới tải open-loop.

## Ghi một run

Mỗi run nằm trong `benchmarks/results/<ngày>-<nhãn>/` với:

- `metadata.md`: máy, OS, JVM, phiên bản Kotlin, coroutines và JMH, commit cùng trạng thái working tree,
  fingerprint các file nguồn liên quan lấy trước và sau khi chạy, lệnh đã chạy, kiểm tra toàn vẹn (số case,
  điểm số hữu hạn);
- JSON và log thô của JMH;
- `analysis.md`: so với run tham chiếu, giới hạn, điểm cần theo dõi.

## Đọc kết quả

- Báo đơn vị rõ: ns hoặc µs cho mỗi flow, batch hay report, bytes cho mỗi operation; ns/event chỉ quy đổi khi
  N > 0 và ghi rõ có gồm chi phí cố định.
- So sánh cùng payload, JVM, kích thước cây và sink. Các case dùng thread pool dao động theo môi trường:
  đối chiếu với `ConcurrencyBenchmark` mode `BASELINE` (không tracing) trước khi gọi một chênh lệch là regression.
- Phân biệt allocation rate với heap giữ lại, latency của sink giả với overhead của core, và tương quan với
  nguyên nhân: profile (JFR qua `-prof jfr`) trước khi kết luận.
- Không đặt ngưỡng pass/fail khi chưa có ngân sách workload thực tế.

## Nguồn phương pháp

- [OpenJDK JMH: dead-code elimination](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_08_DeadCode.java)
- [OpenJDK JMH: per-invocation setup](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_38_PerInvokeSetup.java)
