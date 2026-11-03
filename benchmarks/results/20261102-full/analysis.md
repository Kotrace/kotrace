# Bộ benchmark trước bản phát hành 0.7.0

Ngày đo: 2026-11-02. Commit: `a6aed48fae701070cdc18b6de96ebd57f4b951ea`.
Thông tin máy, fingerprint, lệnh chạy và lần chạy bị dừng nằm trong [metadata.md](metadata.md).

Trạng thái: **thay thế bởi [`20261102-fixed`](../20261102-fixed/analysis.md)**. Lần chạy này đo regression của
report mà commit `d0d8ac6` đã sửa, nên số liệu report ở đây không còn là số tham chiếu.

**Đã chạy đủ 90 trường hợp chính và hai trường hợp sink. Mọi giá trị điểm số đều hữu hạn.**
So với số liệu tháng Mười đã áp dụng các lần đo bổ sung, có **33 trường hợp chậm hơn, 14 trường hợp nhanh hơn
và 43 trường hợp có khoảng tin cậy giao nhau**. Đây là phân loại thời gian, chưa phải bằng chứng về nguyên nhân.

Nguồn tham chiếu là `../20261017-full/main-gc.json`, đối chiếu đúng tên benchmark và toàn bộ tham số.
Mỗi trường hợp có trong `followup-cap.json`, `followup-capture.json`, `followup-conc.json`, `followup-life.json`
hoặc `followup-report.json` dùng số liệu bổ sung thay cho số liệu chính. Tổng cộng 12 trường hợp được thay thế.
Bảng cuối ghi rõ nguồn của từng trường hợp. Các trường hợp còn lại vẫn so với phép đo trước khi sửa timeline và
snapshot trong tháng Mười; vì vậy không thể quy mọi cải thiện cho các thay đổi của 0.6.0 hoặc 0.7.0.

Máy, hệ điều hành, Oracle Java Development Kit 21.0.3, kotlinx.coroutines 1.10.2 và Java Microbenchmark Harness
1.37 giống lần tháng Mười. Profile chính giữ nguyên: ba tiến trình fork, năm lượt warm-up và năm lượt đo, mỗi
lượt một giây, G1, heap một gibibyte, có đo cấp phát bộ nhớ.

## Cách đọc khoảng tin cậy

Với thời gian trung bình và lượng cấp phát, bảng dùng giá trị trung bình ± sai số của khoảng tin cậy **99,9%**
do harness cung cấp. Chỉ gọi một thay đổi là rõ khi hai khoảng không giao nhau, so sánh bằng số chưa làm tròn.
Khoảng giao nhau được ghi là “giao nhau”, kể cả khi hai giá trị trung bình cách xa nhau. Không dùng phần trăm
đơn thuần để kết luận. Các khoảng chưa điều chỉnh cho việc so sánh đồng thời 90 trường hợp.

Mọi thời gian chính bên dưới tính bằng **microsecond mỗi operation**. Một operation là một flow ở lifecycle,
failure và concurrency, một batch ở capture, event-kind, fan-out, sensitive và cap, một lần duyệt ở event-read,
hoặc một report. Đã đổi đúng đơn vị: concurrency được lưu bằng microsecond, 78 trường hợp còn lại bằng
nanosecond. Lượng cấp phát là byte mỗi operation, không phải bộ nhớ trace giữ lại.

## Năm kịch bản trong README

| Kịch bản | 2026-10-17, microsecond | 2026-11-02, microsecond | Cấp phát cũ → mới, byte | Nhận định thời gian |
|---|---:|---:|---:|---|
| Auto-root rỗng, không có cấu hình | 1,008260 ± 0,022757 | 0,992373 ± 0,017593 | 2165,34 → 2152,01 | Giao nhau (-1,58%) |
| Ghi 100 log, không có sink | 3,731492 ± 0,019279 | 3,848917 ± 0,032839 | 29528,03 → 29536,03 | Chậm hơn (+3,15%) |
| Report cây cân bằng 512 span | 57,996762 ± 0,708110 | 75,688557 ± 1,197494 | 195936,40 → 226856,52 | Chậm hơn (+30,50%) |
| Report 128 span, một adapter bỏ qua, trạng thái OK | 1,014456 ± 0,113659 | 1,494937 ± 0,018340 | 6376,01 → 6496,01 | Chậm hơn (+47,36%) |
| 16 worker, mỗi worker 100 log, một span chung dưới cap | 550,488900 ± 17,458404 | 601,113581 ± 20,846378 | 5557975,79 → 5558026,41 | Chậm hơn (+9,20%) |

Các giá trị cũ là số chưa làm tròn tương ứng với năm dòng của README. Batch 100 log chậm hơn 3,15%, report cây
cân bằng 512 span chậm hơn 30,50%, adapter bỏ qua report chậm hơn 47,36%, và span chung 16 worker chậm hơn 9,20%.
Auto-root không có cấu hình có khoảng giao nhau, không có bằng chứng về thay đổi thời gian.

## ReportScaling, ReportOutcome và ReportFailureScaling

**Cả 12 trường hợp `ReportScalingBenchmark` đều chậm hơn rõ**, từ 15,21% đến 52,90%.
Theo thứ tự 1, 16, 128 và 512 span, mức tăng là:

| Hình cây | 1 span | 16 span | 128 span | 512 span |
|---|---:|---:|---:|---:|
| CHAIN | +25,41% | +36,46% | +31,03% | +21,63% |
| STAR | +45,11% | +47,73% | +52,90% | +24,53% |
| BALANCED | +32,91% | +43,29% | +15,21% | +30,50% |

Ứng viên nguyên nhân được ưu tiên cho từng trường hợp này là [quyết định kiến trúc số 044](../../../decisions/adr-044-span-records-in-the-report.md),
commit `a6aed48`: walk thêm `SpanEntry` cho mỗi span, thêm phần tử vào danh sách walk và kiểm tra cổng ngay cả
khi adapter không nhận span record. `FrozenSpans` giữ thêm mảng lifecycle, đọc trạng thái nguyên tử của mọi span,
và với cây lớn tra span qua chỉ số rồi mới lấy timeline hoặc lifecycle. Chỉ số nguyên lớn còn có thể cần boxing.
[Quyết định số 039](../../../decisions/adr-039-adapter-accepts-attribute.md) thêm bước `attributeView` cho mỗi record,
kể cả khi không có thuộc tính để loại; [quyết định số 040](../../../decisions/adr-040-explicit-exception-origin.md)
thêm cổng exception. Đây là suy luận từ đường chạy và cấp phát, không phải phân rã chi phí bằng profiling.

Cây cân bằng 512 span tăng từ 195.936,40 lên 226.856,52 byte mỗi report, tức thêm 30.920,12 byte, khoảng 15,78%.
Report một span tăng từ khoảng 0,1250 đến 0,1430 microsecond lên khoảng 0,1750 đến 0,1810 microsecond; chi phí cố định
cũng tăng, không chỉ hệ số theo số span.

**Cả chín trường hợp `ReportOutcomeBenchmark` ở `REPORT_SKIP` đều chậm hơn rõ.** Với trạng thái theo thứ tự
`OK`, `ERROR`, `CANCELLED`, mức tăng là:

| Số adapter | OK | ERROR | CANCELLED |
|---:|---:|---:|---:|
| 1 | +47,36% | +52,06% | +53,49% |
| 4 | +58,06% | +64,44% | +68,29% |
| 16 | +109,06% | +106,04% | +116,01% |

Nguyên nhân phù hợp nhất cho cả chín trường hợp là phần freeze lifecycle mới của quyết định số 044 cộng với
việc dựng view cho mỗi adapter: các `lazy` cho `acceptsSpanRecords` và `acceptsExplicitException`, cùng các
lambda của bước lọc thuộc tính. `REPORT_SKIP` vẫn không ép chạy tree walk, nhưng vẫn trả chi phí freeze và dựng
sequence. Không thể quy chi phí này cho việc tạo `SpanRecord`, vì record đó không được tạo trong workload này.
Lượng cấp phát của một adapter tăng từ 6.376 lên 6.496 byte; bốn adapter từ 6.496 lên 7.464 byte; 16 adapter từ
6.976 lên 10.728 byte. Việc tăng theo số adapter cho thấy ngoài freeze còn có chi phí view.

**Sáu trường hợp `REPORT_CONSUME` chậm hơn rõ**: một adapter với `OK` (+32,51%), `ERROR` (+35,23%) và
`CANCELLED` (+28,78%); bốn adapter với `OK` (+21,07%) và `CANCELLED` (+13,07%); 16 adapter với `CANCELLED`
(+26,25%). Với từng trường hợp, ứng viên chính vẫn là quyết định số 044, sau đó là bước lọc thuộc tính của
quyết định số 039 và cổng exception của quyết định số 040. Cả 18 trường hợp outcome đều tăng cấp phát với
khoảng không giao nhau, kể cả ba trường hợp consume chưa có bằng chứng về tăng thời gian.

Bốn adapter consume với `ERROR` có trung bình tăng 56,72%, nhưng sai số mới là 16,731069 microsecond nên hai
khoảng còn giao nhau. Consume với 16 adapter ở `OK` và `ERROR` cũng giao nhau. Không gọi ba trường hợp này là
regression về thời gian.

**`ReportFailureScalingBenchmark` không có trường hợp chậm hơn rõ.** 16 span và 128 span có khoảng giao nhau;
512 span nhanh hơn 13,04%, từ 134,647634 ± 9,213463 xuống 117,094383 ± 3,423965 microsecond. Cấp phát tương ứng
giảm từ 485.328,93 xuống 475.240,81 byte. Tham chiếu của cả ba vẫn là phép đo chính tháng Mười, chưa phải phép
đo sau khi tối ưu snapshot. Ứng viên giải thích cải thiện là snapshot chia sẻ mảng và timeline được sửa sau phép
đo chính đó, cùng khác biệt biên dịch tối ưu; không có bằng chứng rằng span entry mới tự làm report nhanh hơn.
Các exception ở đây là lỗi được throw tại child, không phải exception từ `addException`.

## Failure và Lifecycle

Cả ba độ sâu 1, 8 và 32 của `FailureBenchmark.tracedFailureClimb` đều có khoảng giao nhau. Tại độ sâu 8,
14,387968 ± 1,727328 trở thành 13,950656 ± 0,375757 microsecond; tại độ sâu 32, 47,498657 ± 2,716341 trở thành
47,624151 ± 0,657180 microsecond. Lượng cấp phát giảm lần lượt khoảng 56, 416 và 1.792 byte ở ba độ sâu, phù hợp
với timeline gọn hơn so với phép đo chính cũ. Không có regression thời gian đã được xác nhận cho failure climb.

Baseline chỉ dùng coroutine nhanh hơn ở độ sâu 8 (−6,67%) và 32 (−6,01%). Không đi qua kotrace nên các cải thiện
này phản ánh môi trường thực thi hoặc biên dịch tối ưu, không thể gán cho các quyết định kiến trúc mới.

Trong bảy trường hợp lifecycle, chỉ `autoRootReportSkip` nhanh hơn rõ: 1,780909 ± 0,046624 xuống
1,665388 ± 0,036158 microsecond, giảm 6,49%. Tham chiếu vẫn là phép đo chính trước tối ưu timeline và snapshot,
nên các tối ưu đó cùng khác biệt biên dịch là ứng viên phù hợp hơn việc tuyên bố quyết định số 044 làm nhanh hơn.
Sáu trường hợp còn lại, kể cả coroutine có cấu hình và cả ba auto-root không adapter report, đều giao nhau.
Không có bằng chứng về regression lifecycle hay failure nói chung do `ThreadMirror`.

`FailureBenchmark` throw lỗi thường. `ReportOutcomeBenchmark` chỉ truyền `TraceStatus.CANCELLED` vào cùng một
fixture đã dựng, không thực thi một coroutine thực sự bị huỷ trong vùng đo. Vì vậy các số liệu này không đo
riêng đường `executeSpan` đánh dấu `SpanStatus.CANCELLED` mới của quyết định số 044.

## Concurrency, capture và các thay đổi rõ còn lại

Có năm regression concurrency, đều có khoảng thời gian không giao nhau:

| Trường hợp | Cũ, microsecond | Mới, microsecond | Thay đổi | Ứng viên nguyên nhân |
|---|---:|---:|---:|---|
| `SHARED`, 16 worker | 550,488900 ± 17,458404 | 601,113581 ± 20,846378 | +9,20% | Lọc thuộc tính, quyết định số 039; đọc cấu hình qua ThreadMirror |
| `SHARED_CAPPED`, 4 worker | 88,852915 ± 8,031618 | 105,043060 ± 1,088102 | +18,22% | Lọc thuộc tính, quyết định số 039; đọc cấu hình qua ThreadMirror |
| `SHARED_CAPPED`, 16 worker | 587,276154 ± 16,835599 | 645,225813 ± 6,034541 | +9,87% | Lọc thuộc tính, quyết định số 039; đọc cấu hình qua ThreadMirror |
| `CHILDREN`, 1 worker | 21,080354 ± 1,217747 | 29,428981 ± 1,093632 | +39,60% | ThreadMirror và phân giải scope, quyết định số 038; còn bất định về scheduling |
| `CHILDREN`, 4 worker | 45,110533 ± 3,832703 | 50,132667 ± 0,514618 | +11,13% | ThreadMirror và phân giải scope, quyết định số 038; còn bất định về scheduling |

Các workload span chung phát từng log đến live adapter; vì vậy bước lọc thuộc tính của quyết định số 039 chạy
trên mỗi record, còn refactor `ThreadMirror` (`a1c0589`) đổi đường đọc cấu hình và đổi mirror ở mỗi lần chuyển
ngữ cảnh. Với 16 worker vượt cap, thuộc tính `kotrace.events_dropped` có mặt ở các record sau eviction và policy
phải duyệt nó. Lượng cấp phát tăng thêm 24.077 byte mỗi flow, phù hợp với việc duyệt thuộc tính trên đường nóng.
Đây là ứng viên rõ hơn việc quy regression cho span record, bởi các workload concurrency không có report adapter.

`CHILDREN` không có live adapter: quyết định số 039 không có bước giao record để giải thích mức tăng. Ứng viên
trong các thay đổi mới là mirror của các context element và bước phân giải scope của
[quyết định số 038](../../../decisions/adr-038-process-fallback-scope.md), ngay cả khi không cài fallback provider.
Tuy nhiên mức tăng ở một worker lớn hơn điều có thể suy ra từ vài lần đọc thêm; scheduling và cách biên dịch
cũng có thể chi phối. Cần phép đo tách từng commit trước khi kết luận nguyên nhân.

Baseline concurrency không dùng kotrace nhanh hơn rõ ở 1 worker (−38,13%) và 16 worker (−29,98%). 4 worker cũng
có trung bình giảm nhưng khoảng cũ rộng nên giao nhau. `SHARED` với 4 worker nhanh hơn 16,72%, có thể hưởng lợi
từ cùng điều kiện scheduling đó và timeline đã được sửa so với tham chiếu chính. Không coi đây là bằng chứng
rằng mirror hoặc lọc thuộc tính là một tối ưu. Cũng không lấy hai trung bình trừ nhau để tạo khoảng tin cậy mới.

Regression capture được xác nhận là 100 log với `NONE`: +3,15%, tương đương thêm 0,117425 microsecond mỗi batch.
Trường hợp này đã dùng `followup-capture.json`. Không có adapter nên không chạy lọc thuộc tính của quyết định
số 039, và `freshSpan` không gọi phân giải scope. Trong các thay đổi được nêu, ứng viên trực tiếp là đường đọc
cấu hình qua `ThreadMirror` mỗi lần ghi; tác động biên dịch tối ưu hoặc môi trường cũng có thể giải thích chênh
lệch nhỏ này. Lượng cấp phát có khoảng giao nhau, không có bằng chứng về tăng cấp phát ở đây.

Các cải thiện rõ còn lại, liệt kê đủ từng trường hợp:

- `CaptureBenchmark`, 0 event với `LIVE_REJECT` (−16,05%) và `LIVE_ACCEPT` (−15,54%); 10 event với `NONE` (−11,94%). Cả ba dùng tham chiếu chính cũ, giảm 56 byte mỗi batch, phù hợp với timeline gọn hơn sau phép đo đó.
- `EventKindBenchmark`, `NAMED` với `LIVE_REJECT` (−6,79%) và `EXCEPTION` với `LIVE_REJECT` (−5,89%). Tham chiếu chính chưa đo lại sau sửa timeline; mỗi batch giảm khoảng 56 byte. Adapter từ chối nên cổng lọc thuộc tính mới không phải ứng viên giải thích cải thiện.
- `EventReadBenchmark`, 10 event (−4,75%, từ 8,5940 xuống 8,1860 nanosecond). Không chạy report, lọc thuộc tính, fallback scope hay cập nhật context; không có cơ sở gán cải thiện cho các thay đổi mới. Biên dịch tối ưu, bố trí dữ liệu hoặc môi trường là ứng viên phù hợp; chênh lệch tuyệt đối dưới một nanosecond.
- `SensitiveRoutingBenchmark`, `LIVE_ACCEPT` (−10,07%). Policy chặn log sensitive trước khi dựng record; không chạy lọc thuộc tính. Tham chiếu chính trước tối ưu timeline và khác biệt biên dịch là ứng viên, không có tăng hoặc giảm cấp phát được xác nhận.

Tất cả ba trường hợp cap, sáu trường hợp live fan-out và các trường hợp còn lại không nằm trong danh sách thay
đổi rõ đều có khoảng thời gian giao nhau. Bảng đầy đủ bên dưới giữ lại cả sai số lớn thay vì che chúng bằng phần trăm.

## Độ trễ của live sink chặn một millisecond

Bốn giá trị trong README được đối chiếu với số liệu gốc `20260914-sol-high-stage2b/sink-latency.json`, không chỉ
với số đã làm tròn. Bảng tính bằng **millisecond mỗi flow**, cho phân vị thứ 50 và thứ 99.

Harness chỉ cung cấp khoảng tin cậy cho trung bình SampleTime, không cho phân vị: trường `scoreConfidence` của
các phân vị là chuỗi `NaN`. Để vẫn trình bày khoảng cho bốn giá trị được yêu cầu, các khoảng dưới đây được tái
dựng bằng order statistic từ `rawDataHistogram`, với mức danh nghĩa **99,9%**, dưới giả định các mẫu độc lập.
Đây là khoảng thăm dò của phân phối mẫu đã lượng tử hoá, không phải khoảng do harness xuất và không đo đầy đủ
biến thiên giữa các fork hoặc tự tương quan theo thời gian.

Cách tính có thể tái lập: gộp histogram của 15 lượt đo, giữ nguyên tần số; với n mẫu và phân vị p, lấy B có phân
phối nhị thức (n, p). Chọn L là phân vị 0,0005 của B, U là phân vị 0,9995 cộng một. Khoảng là [X[L], X[U]] với
thứ tự bắt đầu từ 1. Nếu L bằng 0 dùng cận dưới 0; nếu U lớn hơn n thì cận trên không bị chặn. Trong bốn cặp
này mọi cận đều hữu hạn. Sai số lượng tử của histogram vẫn còn; hai cận cùng một bin không có nghĩa là thời
gian vật lý được biết chính xác.

| Số record | Phân vị | Cũ, điểm số [khoảng thăm dò] | Mới, điểm số [khoảng thăm dò] | Nhận định có điều kiện |
|---:|---:|---:|---:|---|
| 1 | 50 | 1,275904 [1,273856, 1,277952] | 1,529856 [1,529856, 1,529856] | Chậm hơn (+19,90%) |
| 1 | 99 | 5,423104 [5,038080, 6,103040] | 2,817802 [2,527232, 3,399680] | Nhanh hơn (-48,04%) |
| 10 | 50 | 12,730368 [12,713984, 12,763136] | 15,319040 [15,302656, 15,335424] | Chậm hơn (+20,33%) |
| 10 | 99 | 22,093824 [20,021248, 31,555584] | 21,821194 [19,267584, 34,471936] | Giao nhau (-1,23%) |

Số mẫu cũ → mới là 10.082 → 9.601 cho một record và 1.082 → 978 cho mười record. Hai median tăng khoảng 20%;
phân vị thứ 99 của một record giảm khoảng 48%; phân vị thứ 99 của mười record có khoảng giao nhau. Ba thay đổi
đầu có khoảng thăm dò không giao nhau, nhưng không đủ cơ sở để kết luận regression hoặc cải thiện của core.
Ứng viên được ưu tiên là điều kiện đánh thức thread và scheduling của `LockSupport.parkNanos`, không phải chi phí
tracing thuần. Refactor `ThreadMirror` và lọc thuộc tính vẫn ở đường chạy, nhưng batch này không chuyển
coroutine và payload không có thuộc tính để lọc; vài microsecond tracing không giải thích tốt mức tăng khoảng
0,2540 millisecond cho mỗi record chặn. Fallback scope và `executeSpan` không được dùng trong `freshSpan`.

So với tháng Chín, hệ điều hành đổi từ macOS 14.6.1 lên 27.0.1, kotlinx.coroutines từ 1.11.0 xuống 1.10.2;
lần mới có năm lượt warm-up và profiler cấp phát, lần cũ có ba lượt warm-up và không có profiler đó. Phép đo
mô tả backpressure đồng bộ trong harness, không phải độ trễ production dưới tải open-loop.

Khoảng trung bình SampleTime do harness cung cấp cũng không giao nhau:

| Số record | Trung bình cũ [khoảng 99,9%], millisecond | Trung bình mới [khoảng 99,9%], millisecond |
|---:|---:|---:|
| 1 | 1,492476 [1,461354, 1,523599] | 1,574672 [1,560043, 1,589300] |
| 10 | 14,008108 [13,748923, 14,267293] | 15,587215 [15,449116, 15,725314] |

## Phạm vi còn thiếu: adapter nhận span record

**Không có trường hợp nào đo adapter với `acceptsSpanRecords = true`.** `AcceptAllPolicy` chỉ nhận mọi thuộc
tính; tên đó không có nghĩa là nhận span record. Các số report hiện tại đo chi phí tương thích khi cổng mới vẫn
đóng. Không thể dùng chúng để ước tính trực tiếp chi phí xuất span hoặc backend nhận span.

Một trường hợp bổ sung phù hợp sẽ dùng cùng fixture cây dựng sẵn của `ReportScalingBenchmark`, so sánh một
report adapter có cổng `false` và một adapter có cổng `true`. Dùng các cây CHAIN, STAR và BALANCED với 1, 16,
128, 512 span; tách fixture không event và fixture một log mỗi span. Sink đếm span record và tính checksum từ
identity, status, start và end để tiêu thụ thật các trường. Kiểm tra trước vùng đo số span record đúng bằng số
span được nhận, span record xuất hiện trước event của chính span, status và end đóng băng đúng; giữ policy
thuộc tính và số adapter như nhau. Không thêm serialisation hay hoạt động vào ra vào trường hợp này. Khi đó
chênh lệch giữa hai cổng mới tách được chi phí tạo và giao span record khỏi chi phí entry mà adapter cũ đã trả.
Đây chỉ là đề xuất; không thêm hoặc sửa benchmark trong lần làm việc này.

## Giới hạn và sự cố

- Lần chạy đầu bị người dùng dừng sau 68 trường hợp. Toàn bộ dữ liệu dở dang đã bị xoá theo yêu cầu, chỉ giữ hai log preflight. Phân tích chỉ dùng lần chạy lại đầy đủ.
- Không chạy build, test hoặc lệnh phân tích nặng đồng thời với phép đo. Môi trường không cho liệt kê tiến trình nên không thể xác nhận độc lập mọi ứng dụng khác đều nhàn rỗi. Không tự đóng hoặc thay đổi ứng dụng của người dùng.
- `RTK.md` không có trong repository. Gradle cảnh báo không mở được stream theo dõi hệ thống file; preflight đầu còn cảnh báo không ghi được thiết lập analytics Android. Truy vấn phần cứng có timeout ở kiểm tra activation lock, và một số truy vấn hệ thống bị chặn. Các phép đo vẫn kết thúc thành công, không có fork thất bại hoặc score không hữu hạn.
- Snapshot và timeline tháng Mười chỉ được đo bổ sung ở một tập con. Các so sánh dựa trên số liệu chính chưa áp dụng riêng tối ưu đó không phải phép đo cô lập thay đổi 0.7.0.
- Không chạy profiling theo từng commit. Mọi tên thay đổi gắn với regression hoặc cải thiện ở trên đều là giả thuyết có căn cứ từ đường chạy, không phải kết luận nhân quả.
- Không có ngân sách workload production để đặt ngưỡng đạt hoặc trượt. Chênh lệch có khoảng không giao nhau vẫn có thể rất nhỏ về mặt thực tế.
- Cấp phát chuẩn hoá có 33 trường hợp tăng, 35 trường hợp giảm và 22 trường hợp giao nhau theo cùng quy tắc khoảng. Những giảm dưới một byte ở đường gần như không cấp phát có thể là phần phân bổ chi phí nền của profiler; không diễn giải thành một đối tượng được loại khỏi mỗi operation.
- Phạm vi là core trên Java Virtual Machine HotSpot. Không đo Android ART, OkHttp network, Room database hay bộ nhớ giữ lại.

## Đối chiếu đủ 90 trường hợp

Mỗi bảng ghi thời gian cũ và mới bằng microsecond mỗi operation, cấp phát bằng byte mỗi operation. Ký hiệu ±
là nửa độ rộng khoảng tin cậy 99,9%; số hiển thị đã làm tròn nhưng kết luận dùng toàn bộ độ chính xác của dữ
liệu gốc. Cột cuối phân loại riêng thời gian và cấp phát. Tên file nguồn thuộc thư mục `../20261017-full/`.

### CaptureBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `captureLogBatch (eventCount=0, mode=NONE)` | `followup-capture.json` | 0,028036 ± 0,000502 | 0,028128 ± 0,000547 | +0,33% | 328,0000 ± 0,0000 | 328,0000 ± 0,0000 | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=0, mode=LIVE_REJECT)` | `main-gc.json` | 0,035245 ± 0,002746 | 0,029589 ± 0,000128 | -16,05% | 384,0000 ± 0,0000 | 328,0000 ± 0,0000 | Nhanh hơn; giảm |
| `captureLogBatch (eventCount=0, mode=LIVE_ACCEPT)` | `main-gc.json` | 0,035099 ± 0,002943 | 0,029646 ± 0,000096 | -15,54% | 384,0000 ± 0,0000 | 328,0000 ± 0,0000 | Nhanh hơn; giảm |
| `captureLogBatch (eventCount=10, mode=NONE)` | `main-gc.json` | 0,393269 ± 0,025198 | 0,346325 ± 0,002530 | -11,94% | 1504,0030 ± 0,0000 | 1448,0020 ± 0,0000 | Nhanh hơn; giảm |
| `captureLogBatch (eventCount=10, mode=LIVE_REJECT)` | `main-gc.json` | 0,408158 ± 0,017136 | 0,419524 ± 0,096250 | +2,78% | 1504,0030 ± 0,0000 | 1448,0030 ± 0,0010 | Giao nhau; giảm |
| `captureLogBatch (eventCount=10, mode=LIVE_ACCEPT)` | `main-gc.json` | 0,656419 ± 0,051412 | 0,638168 ± 0,006831 | -2,78% | 3184,0050 ± 0,0000 | 3128,0040 ± 0,0000 | Giao nhau; giảm |
| `captureLogBatch (eventCount=100, mode=NONE)` | `followup-capture.json` | 3,731492 ± 0,019279 | 3,848917 ± 0,032839 | +3,15% | 29528,0260 ± 0,0000 | 29536,0270 ± 12,5200 | Chậm hơn; giao nhau |
| `captureLogBatch (eventCount=100, mode=LIVE_REJECT)` | `main-gc.json` | 4,050747 ± 0,049570 | 4,075942 ± 0,040513 | +0,62% | 29584,0280 ± 0,0000 | 29584,0280 ± 0,0000 | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=100, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,559846 ± 0,307815 | 6,453039 ± 0,175273 | -1,63% | 46416,0450 ± 0,0020 | 46360,0440 ± 0,0010 | Giao nhau; giảm |
| `captureLogBatch (eventCount=1000, mode=NONE)` | `followup-capture.json` | 84,891542 ± 6,906190 | 81,496095 ± 0,799764 | -4,00% | 2092352,5870 ± 0,0480 | 2092352,5630 ± 0,0050 | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=1000, mode=LIVE_REJECT)` | `main-gc.json` | 85,688900 ± 2,282408 | 84,362168 ± 0,716123 | -1,55% | 2092504,5940 ± 0,0140 | 2092448,5830 ± 0,0060 | Giao nhau; giảm |
| `captureLogBatch (eventCount=1000, mode=LIVE_ACCEPT)` | `main-gc.json` | 120,036338 ± 6,716733 | 117,946842 ± 0,747799 | -1,74% | 2267704,8290 ± 0,0470 | 2267648,8130 ± 0,0060 | Giao nhau; giảm |

### ConcurrencyBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `runWorkers (mode=BASELINE, workerCount=1)` | `main-gc.json` | 11,022845 ± 1,730254 | 6,820186 ± 0,217591 | -38,13% | 1287,9590 ± 32,8080 | 1292,3950 ± 0,3720 | Nhanh hơn; giao nhau |
| `runWorkers (mode=BASELINE, workerCount=4)` | `main-gc.json` | 17,840585 ± 10,445483 | 8,516038 ± 0,199788 | -52,27% | 2372,7560 ± 7,9970 | 2369,1810 ± 0,7040 | Giao nhau; giao nhau |
| `runWorkers (mode=BASELINE, workerCount=16)` | `main-gc.json` | 17,678299 ± 1,847469 | 12,377927 ± 0,273244 | -29,98% | 6270,3130 ± 330,8540 | 6057,7870 ± 1,1760 | Nhanh hơn; giao nhau |
| `runWorkers (mode=SHARED, workerCount=1)` | `main-gc.json` | 23,251174 ± 2,065237 | 19,481470 ± 10,562198 | -16,21% | 49293,5730 ± 24,9630 | 49210,5830 ± 9,1880 | Giao nhau; giảm |
| `runWorkers (mode=SHARED, workerCount=4)` | `main-gc.json` | 93,807076 ± 7,166175 | 78,118963 ± 4,500154 | -16,72% | 431292,8020 ± 31,0190 | 431228,2660 ± 47,8520 | Nhanh hơn; giao nhau |
| `runWorkers (mode=SHARED, workerCount=16)` | `followup-conc.json` | 550,488900 ± 17,458404 | 601,113581 ± 20,846378 | +9,20% | 5557975,7870 ± 11,5680 | 5558026,4050 ± 17,7530 | Chậm hơn; tăng |
| `runWorkers (mode=SHARED_CAPPED, workerCount=1)` | `main-gc.json` | 24,610266 ± 2,890702 | 31,283872 ± 4,030529 | +27,12% | 49299,6310 ± 16,8310 | 49237,7180 ± 26,8820 | Giao nhau; giảm |
| `runWorkers (mode=SHARED_CAPPED, workerCount=4)` | `main-gc.json` | 88,852915 ± 8,031618 | 105,043060 ± 1,088102 | +18,22% | 431327,1900 ± 35,8200 | 431405,1970 ± 47,6940 | Chậm hơn; giao nhau |
| `runWorkers (mode=SHARED_CAPPED, workerCount=16)` | `followup-conc.json` | 587,276154 ± 16,835599 | 645,225813 ± 6,034541 | +9,87% | 4888488,8020 ± 17,5370 | 4912565,8010 ± 10,5680 | Chậm hơn; tăng |
| `runWorkers (mode=CHILDREN, workerCount=1)` | `main-gc.json` | 21,080354 ± 1,217747 | 29,428981 ± 1,093632 | +39,60% | 35808,0500 ± 20,3550 | 35714,3910 ± 25,8660 | Chậm hơn; giảm |
| `runWorkers (mode=CHILDREN, workerCount=4)` | `main-gc.json` | 45,110533 ± 3,832703 | 50,132667 ± 0,514618 | +11,13% | 132077,7020 ± 7,8320 | 131814,1070 ± 0,6700 | Chậm hơn; giảm |
| `runWorkers (mode=CHILDREN, workerCount=16)` | `main-gc.json` | 133,043580 ± 7,177826 | 131,258665 ± 0,818365 | -1,34% | 517286,8870 ± 7,7460 | 516344,1760 ± 3,0650 | Giao nhau; giảm |

### EventCapBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `captureCappedBatch (eventCount=2000, maxEvents=100)` | `followup-cap.json` | 142,324579 ± 30,025492 | 133,669574 ± 2,331491 | -6,08% | 1048064,9840 ± 0,2070 | 1048064,9240 ± 0,0180 | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=1000)` | `followup-cap.json` | 311,680027 ± 7,841171 | 317,362146 ± 10,138043 | +1,82% | 6228466,1520 ± 0,0530 | 6228466,1930 ± 0,0720 | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=10000)` | `followup-cap.json` | 330,473019 ± 18,194008 | 338,315502 ± 5,761610 | +2,37% | 8184354,2820 ± 0,1220 | 8184354,3390 ± 0,0410 | Giao nhau; giao nhau |

### EventKindBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `capture100Events (kind=LOG, mode=LIVE_REJECT)` | `main-gc.json` | 5,019648 ± 0,185777 | 4,986858 ± 0,141059 | -0,65% | 30312,0350 ± 0,0010 | 30266,7010 ± 16,6930 | Giao nhau; giảm |
| `capture100Events (kind=LOG, mode=LIVE_ACCEPT)` | `main-gc.json` | 7,751095 ± 0,607965 | 7,420018 ± 0,154975 | -4,27% | 47133,3870 ± 16,6920 | 47066,7180 ± 16,6920 | Giao nhau; giảm |
| `capture100Events (kind=NAMED, mode=LIVE_REJECT)` | `main-gc.json` | 5,141218 ± 0,103632 | 4,792018 ± 0,049226 | -6,79% | 31112,0350 ± 0,0010 | 31056,0330 ± 0,0000 | Nhanh hơn; giảm |
| `capture100Events (kind=NAMED, mode=LIVE_ACCEPT)` | `main-gc.json` | 7,055656 ± 0,438293 | 6,531365 ± 0,108959 | -7,43% | 42344,0490 ± 0,0030 | 42256,0450 ± 0,0010 | Giao nhau; giảm |
| `capture100Events (kind=EXCEPTION, mode=LIVE_REJECT)` | `main-gc.json` | 4,894823 ± 0,123588 | 4,606561 ± 0,071152 | -5,89% | 27912,0340 ± 0,0010 | 27856,0320 ± 0,0010 | Nhanh hơn; giảm |
| `capture100Events (kind=EXCEPTION, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,325575 ± 0,169148 | 6,152277 ± 0,176328 | -2,74% | 39133,3770 ± 16,6920 | 39088,0420 ± 0,0010 | Giao nhau; giảm |

### EventReadBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `traverseSnapshot (eventCount=10)` | `main-gc.json` | 0,008594 ± 0,000135 | 0,008186 ± 0,000102 | -4,75% | 0,0000 ± 0,0000 | 0,0000 ± 0,0000 | Nhanh hơn; giảm |
| `traverseSnapshot (eventCount=100)` | `main-gc.json` | 0,051828 ± 0,001433 | 0,049957 ± 0,001000 | -3,61% | 0,0000 ± 0,0000 | 0,0000 ± 0,0000 | Giao nhau; giao nhau |
| `traverseSnapshot (eventCount=1000)` | `main-gc.json` | 0,514239 ± 0,018098 | 0,502368 ± 0,018861 | -2,31% | 48,0040 ± 0,0000 | 48,0030 ± 0,0000 | Giao nhau; giao nhau |

### FailureBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `coroutineThrowBaseline (depth=1)` | `main-gc.json` | 0,961629 ± 0,039612 | 0,958273 ± 0,022883 | -0,35% | 944,0070 ± 0,0000 | 960,0070 ± 25,0390 | Giao nhau; giao nhau |
| `coroutineThrowBaseline (depth=8)` | `main-gc.json` | 5,549397 ± 0,165464 | 5,179225 ± 0,045257 | -6,67% | 6824,0380 ± 25,0400 | 6840,0360 ± 0,0000 | Nhanh hơn; giao nhau |
| `coroutineThrowBaseline (depth=32)` | `main-gc.json` | 22,942050 ± 0,923655 | 21,564168 ± 0,364862 | -6,01% | 26920,1590 ± 0,0060 | 26920,1490 ± 0,0030 | Nhanh hơn; giảm |
| `tracedFailureClimb (depth=1)` | `main-gc.json` | 3,670519 ± 0,295899 | 3,562608 ± 0,292554 | -2,94% | 4472,0540 ± 0,0560 | 4416,0740 ± 0,0880 | Giao nhau; giảm |
| `tracedFailureClimb (depth=8)` | `main-gc.json` | 14,387968 ± 1,727328 | 13,950656 ± 0,375757 | -3,04% | 20608,1920 ± 0,2050 | 20192,1860 ± 25,0380 | Giao nhau; giảm |
| `tracedFailureClimb (depth=32)` | `main-gc.json` | 47,498657 ± 2,716341 | 47,624151 ± 0,657180 | +0,26% | 77840,4820 ± 24,9250 | 76048,6440 ± 25,0540 | Giao nhau; giảm |

### LifecycleBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `autoRootEmptyConfig` | `main-gc.json` | 1,672742 ± 0,034703 | 1,626000 ± 0,022574 | -2,79% | 2640,0210 ± 0,0300 | 2584,0130 ± 0,0000 | Giao nhau; giảm |
| `autoRootLiveAdapter` | `main-gc.json` | 1,716560 ± 0,103833 | 1,601284 ± 0,052475 | -6,72% | 2640,0280 ± 0,0410 | 2600,0130 ± 25,0390 | Giao nhau; giảm |
| `autoRootNoConfig` | `followup-life.json` | 1,008260 ± 0,022757 | 0,992373 ± 0,017593 | -1,58% | 2165,3420 ± 20,8660 | 2152,0090 ± 0,0000 | Giao nhau; giao nhau |
| `autoRootReportSkip` | `main-gc.json` | 1,780909 ± 0,046624 | 1,665388 ± 0,036158 | -6,49% | 2872,0370 ± 0,0510 | 2856,0130 ± 25,0390 | Nhanh hơn; giao nhau |
| `configuredCoroutineBaseline` | `main-gc.json` | 0,414416 ± 0,055932 | 0,386253 ± 0,065535 | -6,80% | 512,0030 ± 0,0000 | 528,0030 ± 25,0390 | Giao nhau; giao nhau |
| `coroutineBaseline` | `main-gc.json` | 0,081611 ± 0,008278 | 0,079359 ± 0,001871 | -2,76% | 234,6670 ± 20,8660 | 208,0010 ± 0,0000 | Giao nhau; giảm |
| `plain` | `main-gc.json` | 0,000613 ± 0,000037 | 0,000615 ± 0,000064 | +0,32% | 0,0000 ± 0,0000 | 0,0000 ± 0,0000 | Giao nhau; giao nhau |

### LiveFanoutBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `fanOut100Logs (adapterCount=1, mode=LIVE_REJECT)` | `main-gc.json` | 4,526230 ± 0,456302 | 4,188715 ± 0,228855 | -7,46% | 29584,0310 ± 0,0030 | 29546,6960 ± 29,2110 | Giao nhau; giảm |
| `fanOut100Logs (adapterCount=1, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,767444 ± 0,187329 | 6,564904 ± 0,122531 | -2,99% | 46405,3800 ± 16,6930 | 46360,0450 ± 0,0010 | Giao nhau; giảm |
| `fanOut100Logs (adapterCount=4, mode=LIVE_REJECT)` | `main-gc.json` | 4,829820 ± 0,405423 | 4,402235 ± 0,178013 | -8,85% | 29584,0330 ± 0,0030 | 29528,0300 ± 0,0010 | Giao nhau; giảm |
| `fanOut100Logs (adapterCount=4, mode=LIVE_ACCEPT)` | `main-gc.json` | 12,633903 ± 4,985926 | 10,154695 ± 0,330212 | -19,62% | 46408,0870 ± 0,0340 | 46360,0700 ± 0,0020 | Giao nhau; giảm |
| `fanOut100Logs (adapterCount=16, mode=LIVE_REJECT)` | `main-gc.json` | 6,123665 ± 1,329656 | 5,269389 ± 0,272191 | -13,95% | 29584,0420 ± 0,0090 | 29528,0360 ± 0,0020 | Giao nhau; giảm |
| `fanOut100Logs (adapterCount=16, mode=LIVE_ACCEPT)` | `main-gc.json` | 32,889496 ± 3,939158 | 29,523122 ± 0,609871 | -10,24% | 64829,5600 ± 16,6930 | 64760,2040 ± 0,0040 | Giao nhau; giảm |

### ReportFailureScalingBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `reportExceptionBirthplaces (spanCount=16)` | `main-gc.json` | 2,948278 ± 0,450074 | 2,963041 ± 0,096148 | +0,50% | 13320,0200 ± 0,0030 | 13736,0200 ± 0,0010 | Giao nhau; tăng |
| `reportExceptionBirthplaces (spanCount=128)` | `main-gc.json` | 27,592680 ± 1,558264 | 26,288878 ± 0,487412 | -4,73% | 117141,5240 ± 3714,1400 | 118261,5150 ± 16,6900 | Giao nhau; giao nhau |
| `reportExceptionBirthplaces (spanCount=512)` | `main-gc.json` | 134,647634 ± 9,213463 | 117,094383 ± 3,423965 | -13,04% | 485328,9320 ± 0,0640 | 475240,8080 ± 0,0250 | Nhanh hơn; giảm |

### ReportOutcomeBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,014456 ± 0,113659 | 1,494937 ± 0,018340 | +47,36% | 6376,0070 ± 0,0010 | 6496,0100 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 0,991746 ± 0,043796 | 1,508097 ± 0,011982 | +52,06% | 6376,0070 ± 0,0000 | 6496,0100 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 0,986713 ± 0,029357 | 1,514476 ± 0,039554 | +53,49% | 6376,0070 ± 0,0000 | 6496,0100 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 13,127824 ± 1,067989 | 17,395958 ± 0,819407 | +32,51% | 38706,7570 ± 16,6910 | 56600,1200 ± 12,5170 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 13,476185 ± 1,250169 | 18,224404 ± 0,972173 | +35,23% | 38717,4260 ± 16,6940 | 56608,1260 ± 0,0070 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 13,651577 ± 0,736009 | 17,580602 ± 0,424635 | +28,78% | 38696,0940 ± 0,0050 | 56608,1220 ± 0,0030 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,038587 ± 0,070977 | 1,641553 ± 0,036612 | +58,06% | 6496,0070 ± 0,0000 | 7464,0110 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 1,012589 ± 0,016265 | 1,665063 ± 0,064639 | +64,44% | 6496,0070 ± 0,0000 | 7464,0110 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 0,999960 ± 0,112905 | 1,682792 ± 0,038946 | +68,29% | 6496,0070 ± 0,0010 | 7464,0120 ± 0,0000 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 26,547072 ± 0,652739 | 32,141389 ± 1,801757 | +21,07% | 72360,1830 ± 0,0050 | 82344,2220 ± 12,5180 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 28,326002 ± 2,630900 | 44,391672 ± 16,731069 | +56,72% | 72328,1960 ± 50,0850 | 82336,3070 ± 0,1160 | Giao nhau; tăng |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 27,842010 ± 2,552975 | 31,480071 ± 0,825850 | +13,07% | 72360,1920 ± 0,0170 | 82336,2180 ± 0,0060 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,165974 ± 0,103124 | 2,437572 ± 0,085908 | +109,06% | 6976,0080 ± 0,0010 | 10728,0170 ± 0,0010 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 1,126340 ± 0,102384 | 2,320683 ± 0,122519 | +106,04% | 6976,0080 ± 0,0010 | 10728,0160 ± 0,0010 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 1,034864 ± 0,012921 | 2,235437 ± 0,205290 | +116,01% | 6976,0070 ± 0,0000 | 10728,0150 ± 0,0010 | Chậm hơn; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 78,663791 ± 13,948554 | 91,134943 ± 4,406453 | +15,85% | 174216,5450 ± 0,0970 | 185248,6320 ± 0,0310 | Giao nhau; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 78,953174 ± 12,998567 | 88,889734 ± 2,639901 | +12,59% | 174216,5460 ± 0,0880 | 185248,6150 ± 0,0180 | Giao nhau; tăng |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 68,962470 ± 4,229517 | 87,062880 ± 0,630619 | +26,25% | 174216,4780 ± 0,0290 | 185264,6040 ± 12,5190 | Chậm hơn; tăng |

### ReportScalingBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `reportWarmMessages (shape=CHAIN, spanCount=1)` | `main-gc.json` | 0,143420 ± 0,004134 | 0,179862 ± 0,002861 | +25,41% | 744,0010 ± 12,5200 | 856,0010 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=CHAIN, spanCount=16)` | `main-gc.json` | 1,753135 ± 0,181475 | 2,392312 ± 0,050234 | +36,46% | 7122,6790 ± 523,7480 | 8528,0160 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=CHAIN, spanCount=128)` | `main-gc.json` | 16,087482 ± 1,562587 | 21,079339 ± 0,355401 | +31,03% | 52536,1110 ± 4281,7220 | 67688,1450 ± 12,5220 | Chậm hơn; tăng |
| `reportWarmMessages (shape=CHAIN, spanCount=512)` | `main-gc.json` | 78,911334 ± 1,989666 | 95,982072 ± 1,080580 | +21,63% | 217483,7160 ± 16986,8400 | 271728,6640 ± 12,5200 | Chậm hơn; tăng |
| `reportWarmMessages (shape=STAR, spanCount=1)` | `main-gc.json` | 0,124639 ± 0,020433 | 0,180870 ± 0,001206 | +45,11% | 693,3340 ± 66,7710 | 856,0010 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=STAR, spanCount=16)` | `main-gc.json` | 1,434824 ± 0,057897 | 2,119688 ± 0,071418 | +47,73% | 4394,6770 ± 15,0470 | 6144,0150 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=STAR, spanCount=128)` | `main-gc.json` | 11,121179 ± 0,506050 | 17,003727 ± 0,523897 | +52,90% | 29976,0770 ± 0,0040 | 47856,1170 ± 0,0040 | Chậm hơn; tăng |
| `reportWarmMessages (shape=STAR, spanCount=512)` | `main-gc.json` | 59,318103 ± 2,847851 | 73,870980 ± 1,237587 | +24,53% | 135645,7440 ± 17105,8860 | 189728,5100 ± 0,0100 | Chậm hơn; tăng |
| `reportWarmMessages (shape=BALANCED, spanCount=1)` | `followup-report.json` | 0,131991 ± 0,008121 | 0,175429 ± 0,000753 | +32,91% | 616,0010 ± 25,0390 | 856,0010 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=BALANCED, spanCount=16)` | `followup-report.json` | 1,548521 ± 0,032191 | 2,218932 ± 0,032456 | +43,29% | 5760,0110 ± 200,3130 | 7296,0150 ± 0,0000 | Chậm hơn; tăng |
| `reportWarmMessages (shape=BALANCED, spanCount=128)` | `main-gc.json` | 14,193102 ± 1,452204 | 16,351168 ± 0,416689 | +15,21% | 41448,0980 ± 4281,7120 | 56608,1130 ± 0,0030 | Chậm hơn; tăng |
| `reportWarmMessages (shape=BALANCED, spanCount=512)` | `followup-report.json` | 57,996762 ± 0,708110 | 75,688557 ± 1,197494 | +30,50% | 195936,4020 ± 0,0060 | 226856,5220 ± 0,0090 | Chậm hơn; tăng |

### SensitiveRoutingBenchmark

| Phương thức và tham số | Nguồn cũ | Thời gian cũ | Thời gian mới | Thay đổi thời gian | Cấp phát cũ | Cấp phát mới | Nhận định thời gian; cấp phát |
|---|---|---:|---:|---:|---:|---:|---|
| `route100SensitiveLogs (mode=LIVE_ACCEPT)` | `main-gc.json` | 4,413962 ± 0,259443 | 3,969665 ± 0,021355 | -10,07% | 29584,0300 ± 0,0020 | 29592,0270 ± 50,0780 | Nhanh hơn; giao nhau |
| `route100SensitiveLogs (mode=LIVE_SENSITIVE)` | `main-gc.json` | 6,768822 ± 0,172343 | 6,415473 ± 0,262379 | -5,22% | 46405,3800 ± 16,6920 | 46360,0440 ± 0,0020 | Giao nhau; giảm |
