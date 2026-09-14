# Event buffer experiment

Trạng thái: **không khuyến nghị merge candidate hiện tại**. Ngày 2026-09-14.

Candidate thay `CopyOnWriteArrayList` trong `Span.events` bằng một `ArrayList` có lock. Public type vẫn là
`MutableList<SpanEvent>`; concurrent append, per-writer order, snapshot iterator, mutable parent-backed
`subList` và các Java bulk operation được kiểm tra. Post-revert verification phát hiện nested `subList`
không khớp hoàn toàn với COW; xem phần Verification.

Benchmark dùng Oracle JDK 21.0.3, JMH 1.37, G1GC, heap 1 GiB và profile 3 fork × 5 warm-up ×
5 measurement × 1 giây. Candidate có đủ 36/36 primary score, không có fork failure.

## Baseline

- Append/concurrency: `../20260914-sol-high-stage2b/main-gc.json`.
- Read snapshot: `../20260914-event-buffer-baseline/read-gc.json`, đo bổ sung trên implementation COW.
- Report: `../20260914-report-index-candidate-v2/report-gc.json`, tức baseline đã có tree-index candidate v2.

## Kết quả có lợi

| Workload | Baseline | Candidate | Latency | Allocation |
|---|---:|---:|---:|---:|
| append NONE / 100 events | 3.688 ns | 3.154 ns | 1,17× nhanh hơn | −69,9% |
| append NONE / 1.000 events | 84.150 ns | 32.439 ns | 2,59× nhanh hơn | −95,8% |
| append LIVE_ACCEPT / 1.000 events | 118.302 ns | 62.871 ns | 1,88× nhanh hơn | −86,4% |
| shared span / 4 writers | 64,79 µs | 43,53 µs | 1,49× nhanh hơn | −74,5% |
| shared span / 16 writers | 544,70 µs | 171,12 µs | 3,18× nhanh hơn | −92,3% |
| child spans / 16 writers | 136,00 µs | 112,82 µs | 1,21× nhanh hơn | −55,1% |

Allocation append không còn tăng gần bậc hai. Candidate giải quyết đúng hotspot khi một span nhận hàng trăm
đến hàng nghìn event, đặc biệt khi nhiều writer cùng ghi vào span đó.

## Regression

| Workload | Baseline | Candidate | Latency | Allocation |
|---|---:|---:|---:|---:|
| append NONE / 0 events | 18,08 ns | 23,72 ns | +31,2% | +21,4% |
| append NONE / 10 events | 348,69 ns | 352,09 ns | +1,0% | −22,0% |
| read / 10 events | 6,98 ns | 17,14 ns | 2,45× chậm hơn | +56 B/op |
| read / 100 events | 41,59 ns | 66,76 ns | 1,61× chậm hơn | +416 B/op |
| read / 1.000 events | 436,06 ns | 557,14 ns | 1,28× chậm hơn | +4.040 B/op |
| report chain / 512 spans | 64,23 µs | 67,67 µs | +5,4% | +11,8% |
| report star / 512 spans | 36,72 µs | 42,95 µs | +17,0% | +29,8% |
| report balanced / 512 spans | 47,06 µs | 52,67 µs | +11,9% | +14,9% |

Ở trace 0 event, mỗi `Span` trả thêm 48 B cho lock + mutable backing store. Snapshot iterator phải copy list
khi đọc, nên report—workload một event trên mỗi span—chậm hơn ở phần lớn shape/size. Regression report tại
16 spans là 13,0–19,3%; tại 512 spans là 5,4–17,0%.

## Verification

- Contract test pass cho concurrent append 4 × 1.000 event, không mất event và giữ order từng writer.
- Snapshot iterator ổn định và không cho mutate.
- `subList` là parent-backed view và phát hiện parent mutation. Post-revert verification còn chỉ ra một
  khác biệt compatibility: COW invalidates ancestor view sau nested mutation, trong khi candidate giữ ancestor
  sống; đây là thêm một lý do không merge candidate.
- `sort`, `replaceAll`, `removeIf` vẫn hoạt động qua public `MutableList`.
- `./gradlew :test :benchmarks:test --rerun-tasks` pass.
- Full build/Dokka chưa chạy vì candidate không qua performance gate.

## Quyết định và điểm dừng

Không merge candidate này: nó tối ưu mạnh trường hợp event-dense/shared-span nhưng tăng code và chi phí ở
đường phổ biến hơn là span có 0–10 event rồi report một lần. Source đã được đưa về COW; benchmark, contract
test và artifact được giữ làm regression proof. Chỉ nên thử candidate khác nếu workload thực tế chứng minh
event-dense span là trường hợp ưu tiên, hoặc dùng thiết kế segmented/snapshot-cache kèm benchmark cold-read
để không che chi phí report đầu tiên.
