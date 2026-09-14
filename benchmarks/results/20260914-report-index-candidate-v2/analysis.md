# Report tree index experiment — candidate v2

Trạng thái: **giữ candidate v2**. Ngày 2026-09-14.

Baseline là `20260914-sol-high-stage2b/main-gc.json`. Candidate dùng cùng Oracle JDK 21.0.3,
JMH 1.37, G1GC, heap 1 GiB và profile 3 fork × 5 warm-up × 5 measurement × 1 giây.
15/15 primary score hợp lệ; không có fork failure.

## Thay đổi

- Dựng `parentId → children` một lần và chỉ sort mỗi sibling bucket một lần.
- Tính birthplace exception bằng một post-order traversal dùng identity set.
- Merge subtree key set theo small-to-large để không copy toàn bộ lineage set ở mỗi ancestor.
- Không cấp phát child index cho trace một span và không tạo birthplace map khi trace không có exception.
- `reportTrace` và `renderTree` dùng chung `TraceTreeIndex`, giữ nguyên DFS/start-time order.

Candidate v1 đã bị loại vì luôn dựng `groupBy`/`IdentityHashMap`: trace một span tăng từ khoảng 36–41 ns
lên 85–87 ns và từ 240 lên 992 B/op. V2 loại hai allocation vô ích này trước khi chạy lại toàn suite.

## Paired result

| Shape / spans | Baseline | Candidate v2 | Speedup | Allocation thay đổi |
|---|---:|---:|---:|---:|
| chain / 1 | 41,4 ns | 36,0 ± 1,0 ns | 1,15× | −23,3% |
| chain / 16 | 1,36 µs | 1,32 ± 0,05 µs | 1,03× | +22,0% |
| chain / 128 | 41,28 µs | 13,82 ± 3,52 µs | 2,99× | +0,1% |
| chain / 512 | 758,49 µs | 64,23 ± 6,46 µs | 11,81× | +18,8% |
| star / 1 | 36,4 ns | 35,3 ± 0,5 ns | 1,03× | −23,3% |
| star / 16 | 1,66 µs | 0,96 ± 0,02 µs | 1,74× | −18,1% |
| star / 128 | 35,96 µs | 7,47 ± 0,36 µs | 4,81× | −37,9% |
| star / 512 | 473,43 µs | 36,72 ± 3,32 µs | 12,89× | −25,8% |
| balanced / 1 | 36,8 ns | 34,9 ± 0,2 ns | 1,05× | −23,3% |
| balanced / 16 | 1,65 µs | 1,08 ± 0,02 µs | 1,52× | −9,1% |
| balanced / 128 | 41,53 µs | 9,03 ± 1,82 µs | 4,60× | −23,2% |
| balanced / 512 | 528,39 µs | 47,06 ± 1,96 µs | 11,23× | −4,7% |

Với 16 → 512 span (32× số node), candidate latency chỉ tăng 38–49× thay vì 285–557×.
Số mũ quan sát giảm từ khoảng 1,6–1,8 xuống 1,05–1,12, gần tuyến tính trong miền đã đo.

Chain vẫn là trade-off allocation: mỗi parent có một singleton child bucket, nên 512-span candidate dùng
207.632 B/op so với baseline 174.792 B/op (+18,8%). Đổi lại latency giảm 11,81×. Không nên thêm một cấu
trúc index phức tạp hơn chỉ để loại phần này nếu chưa có memory budget thực tế.

## Exception-heavy diagnostic

Fixture star có một exception lineage riêng trên mỗi child, đã dựng ngoài vùng timed:

| Spans | Candidate score | Allocation |
|---:|---:|---:|
| 16 | 1,49 ± 0,10 µs | 9.984 B/op |
| 128 | 16,25 ± 1,61 µs | 90.920 B/op |
| 512 | 89,39 ± 8,34 µs | 411.233 B/op |

16 → 512 span làm latency tăng 60,1× và allocation tăng 41,2×; vẫn vượt tuyến tính nhẹ nhưng không còn
kiểu tăng gần bậc hai của full-list descendant scan cũ. Fixture này không có paired baseline cũ, nên chỉ
dùng để xác nhận candidate không tái tạo hotspot lineage ở tree nhiều exception.

## Verification

- Cùng bộ `ExceptionLineageTest`, `LogTest`, `TraceTreeTest`, `AutoRootSpanTest` pass trước và sau refactor.
- Toàn bộ core tests và benchmark preflight pass với `--rerun-tasks`.
- Fixture exception-heavy 31 span tạo đúng 30 birthplace record.
- `./gradlew dokkaGenerate build` pass: 89 tasks, gồm core, benchmark, demo, OkHttp, Room/lint và Dokka.
- Public API, wire format, adapter contract và module split không đổi.

## Quyết định và điểm dừng

Giữ candidate v2: regression trace nhỏ đã được loại, speedup trace lớn nhất quán và correctness proof giữ
nguyên. Không tự động chuyển sang thay event buffer; đó là ownership boundary khác, có rủi ro ordering và
concurrency cao hơn, cần một experiment riêng sau xác nhận.
