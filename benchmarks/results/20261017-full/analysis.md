# Full suite after ADR-037

Trạng thái: **số liệu tham chiếu mới**, thay cho bảng trong README. Ngày 2026-10-17.

Baseline so sánh là `20260914-sol-high-stage2b/main-gc.json`, chạy trước report index, ADR-029 và mọi ADR sau
đó. Cùng máy, cùng Oracle JDK 21.0.3, JMH 1.37, G1GC, heap 1 GiB, profile 3 fork × 5 warm-up × 5 measurement
× 1 giây. 90/90 primary score hợp lệ. Môi trường khác ở hai điểm, xem mục Giới hạn.

## Kịch bản chính

| Kịch bản | 2026-09-14 | 2026-10-17 | Thay đổi |
|---|---:|---:|---:|
| Auto-root rỗng, không config | 8,14 µs, 14,5 KiB | 1,12 ± 0,22 µs, 2,2 KiB | 7,3× nhanh hơn |
| Auto-root có một live adapter | 8,42 µs, 14,9 KiB | 1,72 ± 0,10 µs, 2,6 KiB | 4,9× |
| Capture 100 log, không sink | 3,69 µs, 28,7 KiB | 3,83 ± 0,09 µs, 28,9 KiB | +4% |
| Capture 100 log, một live adapter nhận | 6,43 µs, 49,9 KiB | 6,56 ± 0,31 µs, 45,3 KiB | +2% |
| Report cây cân bằng 512 span | 528,39 µs, 168,7 KiB | 66,89 ± 2,84 µs, 179,4 KiB | 7,9× |
| Report 128 span, adapter bỏ qua theo status | 37,82 µs, 34,5 KiB | 1,01 ± 0,11 µs, 6,2 KiB | 37× |
| Report 128 span, một adapter đọc | 39,14 µs, 42,7 KiB | 13,13 ± 1,07 µs, 37,8 KiB | 3,0× |
| Failure leo qua 8 span | 38,61 µs, 61,9 KiB | 14,39 ± 1,73 µs, 20,1 KiB | 2,7× |
| 16 worker × 100 log, span chung, không loại event | 544,70 µs, 5,35 MiB | 592,54 ± 37,17 µs, 5,30 MiB | +9% |
| 16 worker × 100 log, span chung, cap mặc định | không có | 738,23 ± 23,69 µs, 6,96 MiB | xem mục Cap |

Phần lớn chênh lệch đến từ những thay đổi đã có ADR: id bằng bảng tra và report walk lười (ADR-029), report
index (`20260914-report-index-candidate-v2`).

## Report walk

Sau ADR-029, report 512 span cân bằng đo được khoảng 78 µs, chậm hơn 66% so với report index v2 (47,06 µs).
Đo lại từng commit (2 fork × 3 warm-up × 5 measurement, chỉ benchmark này) cho thấy không có một regression đơn lẻ
mà là chi phí cộng dồn của các tính năng:

| Commit | Thay đổi | Thời gian | Allocation |
|---|---|---:|---:|
| `8933dc0` | suite benchmark đầu tiên, sau report index | 46,9 µs | 160,9 KiB |
| `145d274` | ADR-019, đánh dấu BIRTHPLACE / PROPAGATED | 53,2 µs | 104,9 KiB |
| `a347c5e` | ADR-025 | 59,2 µs | 132,9 KiB |
| `f9e50f7` | ADR-026, report mọi root | 65,8 µs | 171,1 KiB |
| `ed916be` | ADR-029, đóng băng report | 78,3 µs | 203,4 KiB |
| `010ab79` (HEAD) | | 77,2 µs | 203,4 KiB |

ADR-029 tự nó tốn 21%, đúng mức ADR đã ghi. Profile JFR cho thấy phần thừa nằm ở cấu trúc dữ liệu, nên walk được
cắt mà không đổi hành vi:

- `TraceTreeIndex` đọc thẳng `FrozenTimelines`, bỏ một `IdentityHashMap` giữ lại cùng snapshot; `renderTree` cũng
  dựng `FrozenTimelines`;
- `HashMap` và `HashSet` của index được cấp đủ dung lượng từ đầu, không resize;
- sắp xếp bằng comparator so sánh `Long` nguyên thủy, không box mỗi lần so sánh.

Kết quả: 66,89 µs và 179,4 KiB trong suite này, giảm khoảng 14% thời gian và 12% allocation so với HEAD. Một
thử nghiệm drain snapshot vào `ArrayList` bị loại: `ArrayList` mặc định cấp mảng 10 phần tử cho mỗi span, nên
allocation tăng lên 249 KiB.

## Cap event trên mỗi span

`EventCapBenchmark` ghi 2.000 log vào một span, không sink:

| Cap | Thời gian | Allocation |
|---:|---:|---:|
| 100 | 176,69 ± 6,53 µs | 1,75 MiB |
| 1.000 | 506,32 ± 85,06 µs | 9,77 MiB |
| 10.000 (không loại) | 340,09 ± 15,15 µs | 7,81 MiB |

Cap nhỏ rẻ nhất vì mỗi lần copy chỉ chép 100 phần tử. Ở cap 1.000, mỗi append sau cap tốn hai lần copy toàn mảng
(`removeAt` rồi `add` trên `CopyOnWriteArrayList`), nên đắt hơn cả việc giữ hết 2.000 event. Với span chung 16
worker, cap mặc định làm chậm thêm 25% (592 → 738 µs) vì cùng lý do. Đã sửa, xem mục Sau khi sửa.

## Cần theo dõi

1. **Report một span: 131 đến 143 ns, 0,7 KiB**, so với 36 đến 41 ns và 0,2 KiB trước đây. Ban đầu nghi
   `FrozenTimelines` (hai `IdentityHashMap`), nhưng bỏ chúng không đổi thời gian (xem mục Sau khi sửa). Profile
   cho thấy chi phí rải đều trên bộ máy walk lười của ADR-019/029: lambda và `lazy` của `reportTrace`, ba
   `Sequence` lọc của mỗi adapter, `ArrayList` của walk. Đó là chi phí cố định khoảng 100 ns mỗi report, nhỏ so
   với khoảng 1 µs của một span, nên được giữ nguyên.
2. **Report đến 16 adapter đọc: 170,1 KiB, so với 36,8 KiB.** Mỗi adapter dựng `TraceRecord` riêng cho mỗi entry
   (view lọc theo policy của từng adapter, ADR-019), nên allocation tỉ lệ với số adapter.
3. **Loại event ở cap tốn hai lần copy.** Đã sửa, xem mục Sau khi sửa.
4. **Fan-out live đến 4 hoặc 16 adapter: +23% đến +45%**, nhưng sai số lớn (đến ±5 µs). Cần đo lại riêng trước
   khi kết luận.
5. **Batch capture rỗng: 31 đến 35 ns, so với 18 đến 25 ns.** Dựng `Span` giờ chép traits và links thành view
   không sửa được và đọc cap; chênh lệch tuyệt đối khoảng 13 ns mỗi span. Sau khi sửa, span rỗng không còn cấp
   mảng riêng: 28,04 ns.

## Sau khi sửa

Hai thay đổi, đo lại các trường hợp bị ảnh hưởng cùng profile 3 fork (`followup-*.json`):

- `EventTimeline` thay `CopyOnWriteArrayList`: một append quá cap loại event và thêm event trong **một** lần copy;
  mọi append đi qua một lock nên cap chính xác; span rỗng dùng chung một mảng rỗng.
- `FrozenTimelines` lấy snapshot O(1) dùng chung mảng của timeline, không còn drain iterator thành list; trace
  tối đa 8 span tra bằng cách duyệt, không dựng map.

| Kịch bản | Trước | Sau | Thay đổi |
|---|---:|---:|---:|
| 2.000 log, cap 100 | 176,69 µs, 1,75 MiB | 142,32 ± 30,03 µs, 1,00 MiB | −19%, allocation −43% |
| 2.000 log, cap 1.000 | 506,32 µs, 9,77 MiB | 311,68 ± 7,84 µs, 5,94 MiB | −38%, −39% |
| 2.000 log, cap 10.000 (không loại) | 340,09 µs, 7,81 MiB | 330,47 ± 18,19 µs, 7,81 MiB | không đổi |
| 16 worker, span chung, cap mặc định | 738,23 µs, 6,96 MiB | 587,28 ± 16,84 µs, 4,66 MiB | −20%, −33% |
| 16 worker, span chung, dưới cap | 592,54 µs | 550,49 ± 17,46 µs | −7% |
| Report cây cân bằng 512 span | 66,89 µs, 179,4 KiB | 58,00 ± 0,71 µs, 191,3 KiB | −13%, +7% |
| Report một span | 131,55 ns, 0,68 KiB | 131,99 ± 8,12 ns, 0,60 KiB | không đổi |
| Batch capture rỗng | 31,16 ns | 28,04 ± 0,50 ns | −10% |
| Auto-root rỗng | 1,12 µs | 1,01 ± 0,02 µs | −10% |

Ở cap 1.000, loại event giờ còn rẻ hơn giữ hết 2.000 event, vì mỗi lần copy chỉ chép 1.000 phần tử.

## Giới hạn

- macOS đã lên 27.0.1 (baseline 14.6.1) và coroutines là 1.10.2 (baseline 1.11.0). Các trường hợp dùng thread
  pool dao động theo môi trường: `ConcurrencyBenchmark` mode `BASELINE`, vốn không dùng kotrace, chậm hơn
  1,4 đến 2,0×. Vì vậy các chênh lệch nhỏ ở `SHARED` với 1 và 4 worker không được coi là regression của kotrace.
- `SinkLatencyBenchmark` không chạy lại.
- Allocation B/op là lượng cấp phát, không phải bộ nhớ giữ lại.
