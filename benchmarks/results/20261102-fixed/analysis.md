# Bộ benchmark sau khi sửa hiệu năng report cho 0.7.0

Ngày đo: 2026-11-02. Commit: `d0d8ac6dcfb02169997cf9f2ac2dbba693857f83`.
Thông tin máy, fingerprint, lệnh chạy và kiểm tra toàn vẹn nằm trong [metadata.md](metadata.md).

**Đã chạy đủ 90 trường hợp chính. Cả 447 điểm số chính và phụ, cùng 1.350 phép đo thời gian gốc, đều hữu hạn.**
So với 0.5.0, có **8 trường hợp chậm hơn, 21 trường hợp nhanh hơn và 61 trường hợp có khoảng tin cậy giao nhau**.
So với lần trước khi sửa, có **6 trường hợp chậm hơn, 41 trường hợp nhanh hơn và 43 trường hợp giao nhau**.
Đây là phân loại thời gian theo khoảng tin cậy, chưa phải kết luận về nguyên nhân.

Nguồn tham chiếu 0.5.0 là `../20261017-full/main-gc.json`, đối chiếu đúng tên benchmark và toàn bộ tham số.
Mọi trường hợp có trong `followup-cap.json`, `followup-capture.json`, `followup-conc.json`, `followup-life.json`
hoặc `followup-report.json` dùng kết quả bổ sung thay cho kết quả chính. Tổng cộng 12 trường hợp được thay thế.
Nguồn trước khi sửa là `../20261102-full/main-gc.json`, đo commit `a6aed48`, trước `d0d8ac6`.
Lần `20261102-full` được giữ làm bằng chứng về regression, không dùng làm số liệu hiện hành của 0.7.0.
Lần đầy đủ trong thư mục này mới là nguồn số liệu sau khi sửa; README không được chỉnh trong phiên làm việc này.

Máy, macOS 27.0.1, Oracle Java Development Kit 21.0.3, kotlinx.coroutines 1.10.2 và Java Microbenchmark Harness
1.37 giữ như hai lần tham chiếu. Cấu hình đo là ba tiến trình fork, năm lượt làm nóng và năm lượt đo, mỗi lượt
một giây, một luồng harness, G1 và heap một gibibyte, có đo cấp phát bộ nhớ.

## Cách đọc khoảng tin cậy

Thời gian và cấp phát được ghi bằng trung bình ± sai số của khoảng tin cậy **99,9%** do harness cung cấp.
Chỉ gọi thay đổi là rõ khi hai khoảng không giao nhau, so sánh bằng cận chưa làm tròn trong `scoreConfidence`.
Khoảng giao nhau được ghi là “giao nhau”, kể cả khi trung bình khác xa nhau. Các khoảng không được điều chỉnh
cho việc so sánh đồng thời nhiều trường hợp. Không suy ra quan hệ nhân quả chỉ từ phần trăm hoặc khoảng tách nhau.

Thời gian trong toàn bộ các bảng chính tính bằng **microsecond mỗi operation**. Một operation là một flow ở
lifecycle, failure và concurrency; một batch ở capture, event-kind, fan-out, sensitive và giới hạn event; một
lần duyệt ở event-read; hoặc một report. Mười hai trường hợp concurrency vốn được lưu bằng microsecond, còn
78 trường hợp khác được đổi từ nanosecond. Cấp phát tính bằng **byte mỗi operation**, không phải bộ nhớ giữ lại.
Mức tăng dưới một byte có thể đến từ cách bộ đo phân bổ chi phí nền, không chứng minh có thêm đối tượng mỗi lần gọi.

## Năm kịch bản trong README

Thời gian trung bình và khoảng tin cậy 99,9%, tính bằng microsecond mỗi flow, batch hoặc report tương ứng:

| Kịch bản | 0.5.0, tháng Mười | Trước khi sửa | Sau khi sửa | So với 0.5.0 | So với trước khi sửa |
|---|---:|---:|---:|---|---|
| Auto-root rỗng, không có cấu hình | 1,008260 ± 0,022757 | 0,992373 ± 0,017593 | 0,970507 ± 0,018683 | Giao nhau (-3,74%) | Giao nhau (-2,20%) |
| Ghi 100 log, không có sink | 3,731492 ± 0,019279 | 3,848917 ± 0,032839 | 3,871584 ± 0,044791 | Chậm hơn (+3,75%) | Giao nhau (+0,59%) |
| Report cây cân bằng 512 span | 57,996762 ± 0,708110 | 75,688557 ± 1,197494 | 59,830422 ± 0,601964 | Chậm hơn (+3,16%) | Nhanh hơn (-20,95%) |
| Report 128 span, một adapter bỏ qua, trạng thái OK | 1,014456 ± 0,113659 | 1,494937 ± 0,018340 | 0,948504 ± 0,026027 | Giao nhau (-6,50%) | Nhanh hơn (-36,55%) |
| 16 worker, mỗi worker 100 log, một span chung dưới giới hạn | 550,488900 ± 17,458404 | 601,113581 ± 20,846378 | 538,363136 ± 5,912425 | Giao nhau (-2,20%) | Nhanh hơn (-10,44%) |

Cấp phát của cùng năm kịch bản, tính bằng byte mỗi operation, cũng kèm khoảng tin cậy 99,9%:

| Kịch bản | 0.5.0, tháng Mười | Trước khi sửa | Sau khi sửa |
|---|---:|---:|---:|
| Auto-root rỗng, không có cấu hình | 2165,342231 ± 20,865925 | 2152,008794 ± 0,000121 | 2152,008638 ± 0,000136 |
| Ghi 100 log, không có sink | 29528,025709 ± 0,000116 | 29536,026515 ± 12,519524 | 29528,026699 ± 0,000356 |
| Report cây cân bằng 512 span | 195936,401994 ± 0,005546 | 226856,521615 ± 0,008940 | 200267,079687 ± 20,868579 |
| Report 128 span, một adapter bỏ qua, trạng thái OK | 6376,006999 ± 0,000787 | 6496,010301 ± 0,000132 | 5498,673204 ± 91,810127 |
| 16 worker, mỗi worker 100 log, một span chung dưới giới hạn | 5557975,786680 ± 11,567707 | 5558026,405177 ± 17,753364 | 5557981,790118 ± 12,491726 |

Report cây cân bằng 512 span giảm 20,95% thời gian và 11,72% cấp phát so với trước khi sửa. So với 0.5.0,
thời gian vẫn tăng rõ 3,16% và cấp phát tăng 2,21%. Report bị một adapter bỏ qua giảm rõ 36,55% so với trước
khi sửa; khoảng thời gian còn giao với 0.5.0, nên không gọi mức giảm trung bình 6,50% là cải thiện đã xác nhận.
Auto-root rỗng và span chung 16 worker cũng có khoảng giao với 0.5.0. Capture 100 log không sink vẫn chậm hơn
rõ 3,75%, trong khi khoảng thời gian giao với lần trước khi sửa.

## Regression của report: khoanh vùng, nguyên nhân và bản sửa

### Năm trường hợp đo lại theo commit

Bộ dữ liệu do người dùng cung cấp dùng cùng máy và cấu hình ba fork, năm lượt làm nóng, năm lượt đo một giây,
có đo cấp phát. `run.sh` gốc ghi rõ lựa chọn `ReportOutcomeBenchmark` ở trạng thái `OK`, một hoặc 16 adapter,
với hai chế độ; cùng `ReportScalingBenchmark` cây cân bằng 512 span. Tham số `spanCount=512` áp dụng cho
benchmark scaling, không đổi fixture 128 span cố định của outcome.

- `v0.6.0`: `f912632d6b5267d8a782f67c9eef40f81ddd93bd`, file `bisect/v060.json`.
- `a6aed48^`: `01c1721fa2a85f1abd6bf092e8e27bda238a1d80`, file `bisect/pre044.json`.
- `a6aed48`: `a6aed48fae701070cdc18b6de96ebd57f4b951ea`, file `bisect/head.json`.
- Bản sửa trước commit: `bisect/fix.json`. Đây là phép đo bản sửa chưa commit, tách khỏi lần đầy đủ tại HEAD hiện tại.

Thời gian bằng microsecond mỗi report, trung bình ± sai số khoảng tin cậy 99,9%:

| Trường hợp | v0.6.0 | a6aed48^ | a6aed48 | Bản sửa trước commit |
|---|---:|---:|---:|---:|
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=OK)` | 0,810160 ± 0,014065 | 0,812050 ± 0,025364 | 1,500517 ± 0,055720 | 0,933986 ± 0,028769 |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | 14,516972 ± 0,433466 | 14,411375 ± 0,205284 | 17,175932 ± 0,293384 | 13,567744 ± 0,523082 |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | 1,484209 ± 0,050647 | 1,563355 ± 0,038126 | 2,167929 ± 0,215149 | 1,566478 ± 0,111090 |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | 93,776094 ± 1,503232 | 93,454630 ± 1,335548 | 87,804987 ± 2,393357 | 80,891476 ± 2,137237 |
| `reportWarmMessages (shape=BALANCED, spanCount=512)` | 68,018635 ± 2,143692 | 69,452669 ± 1,640514 | 79,510929 ± 3,021098 | 65,008982 ± 2,567653 |

Cấp phát của năm trường hợp trên, tính bằng byte mỗi report:

| Trường hợp | v0.6.0 | a6aed48^ | a6aed48 | Bản sửa trước commit |
|---|---:|---:|---:|---:|
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=OK)` | 5392,005576 ± 0,000101 | 5392,005602 ± 0,000176 | 6496,010352 ± 0,000402 | 5440,006432 ± 0,000193 |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | 50088,100118 ± 0,002985 | 50088,099569 ± 0,001450 | 56600,118450 ± 12,521007 | 51184,093778 ± 0,003647 |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | 10048,010221 ± 0,000351 | 10048,010776 ± 0,000270 | 10728,014944 ± 0,001481 | 10056,010788 ± 0,000739 |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | 180072,649651 ± 0,011752 | 180072,647979 ± 0,010151 | 185248,609092 ± 0,016650 | 180808,560743 ± 0,015503 |
| `reportWarmMessages (shape=BALANCED, spanCount=512)` | 196112,471340 ± 0,015273 | 196112,481559 ± 0,013260 | 226848,551307 ± 12,510710 | 200240,450509 ± 0,017764 |

`v0.6.0` và `a6aed48^` có khoảng thời gian giao nhau ở cả năm trường hợp. Sau quyết định số 044, bốn trong
năm trường hợp chậm hơn rõ so với commit cha. Ngoại lệ cần giữ trong hồ sơ: report được 16 adapter đọc ở
`OK` nhanh hơn rõ trong phép đo này, từ 93,454630 ± 1,335548 xuống 87,804987 ± 2,393357 microsecond,
trong khi cấp phát vẫn tăng. Vì vậy bộ đo khoanh vùng không chứng minh quyết định số 044 làm chậm mọi hình
thức report. Bản sửa nhanh hơn rõ `a6aed48` ở cả năm trường hợp.

So với `a6aed48^`, bản sửa trước commit vẫn chậm hơn ở một adapter bỏ qua, có khoảng giao ở 16 adapter bỏ
qua, và nhanh hơn ở ba trường hợp đọc report. Phép đo theo commit và phép đo đầy đủ là hai lượt độc lập:
cây cân bằng 512 span của bản sửa trước commit đạt 65,008982 ± 2,567653 microsecond, còn lần đầy đủ hiện tại
đạt 59,830422 ± 0,601964 microsecond. Hai khoảng không giao nhau. Không trộn hai lượt hoặc chọn số thuận lợi
từ mỗi lượt; biến thiên giữa các lần chạy, biên dịch tối ưu và điều kiện hệ thống vẫn là giới hạn của việc quy
nguyên nhân. Số README lấy nhất quán từ lần đầy đủ hiện tại.

### Nguyên nhân đã khoanh vùng và thay đổi trong d0d8ac6

[Quyết định kiến trúc số 044](../../../decisions/adr-044-span-records-in-the-report.md), tại `a6aed48`, đóng
băng lifecycle của mọi span bằng mảng timeline, mảng lifecycle và map từ span sang chỉ số. Chỉ số lớn hơn 127
còn có thể cần boxing. Việc này xảy ra trước cổng outcome, nên adapter bỏ qua cũng trả chi phí. Khi report
được đọc, walk tạo thêm một `SpanEntry` cho mỗi span ngay cả khi không adapter nào nhận span record.
Đó là đường chi phí mà bộ đo theo commit khoanh vùng; khác biệt cấp phát hỗ trợ cách giải thích từ mã nguồn.

`git show d0d8ac6` ghi nhận ba thay đổi liên quan:

- Trạng thái và thời điểm kết thúc của span đã kết thúc không đổi nữa. `markStatus` và `markEnd` không sửa span đã kết thúc, nhất quán với `tryComplete`.
- `FrozenSpans` chỉ giữ bản chụp lifecycle của span còn mở lúc report bắt đầu. Timeline được tra trực tiếp qua map như trước quyết định số 044; không cần mảng lifecycle của mọi span hoặc map chỉ số có boxing.
- Walk giữ danh sách span theo thứ tự và mảng offset vào danh sách event. View chỉ tạo span record khi adapter thực sự bật cổng. Không còn một `SpanEntry` được cấp phát cho mỗi span.

Fixture report của suite tạo cây bằng `span {}` và chờ toàn bộ cây kết thúc trước khi đo, nên đo đúng đường
span đã kết thúc được tối ưu. Lần này không định lượng riêng chi phí giữ lifecycle của span còn mở.
Thông tin trạng thái và end vẫn được cố định tại thời điểm bắt đầu report; event và thứ tự record giữ nguyên.

### Kết quả trên toàn bộ đường report

**Cả 33 trường hợp report đều nhanh hơn rõ so với `20261102-full`:** 12 trường hợp scaling, 18 trường hợp
outcome và ba trường hợp exception birthplace. Không có regression thời gian report so với lần trước khi sửa.

So với 0.5.0, bảy regression thời gian report còn lại là ba trường hợp `REPORT_SKIP` với 16 adapter,
`REPORT_CONSUME` với 16 adapter ở `CANCELLED`, cây CHAIN 512 span và cây BALANCED 16 hoặc 512 span.
Các trường hợp cụ thể cùng giả thuyết nguyên nhân nằm ở bảng tiếp theo. Các trường hợp khác không được gọi
là regression khi khoảng còn giao nhau, kể cả khi trung bình tăng.

Report có một adapter đọc ở cả ba trạng thái nay có khoảng thời gian giao với 0.5.0, dù cấp phát vẫn cao hơn.
Ba trường hợp exception birthplace đều nhanh hơn rõ 0.5.0, từ 19,50% đến 22,64%; các tham chiếu này vẫn là
lần chính tháng Mười, trước tối ưu timeline và snapshot ở các phép đo bổ sung.

## Tám regression thời gian so với 0.5.0

Đây là toàn bộ trường hợp có khoảng thời gian không giao nhau theo hướng chậm hơn. Mọi nguyên nhân dưới đây
là giả thuyết dựa trên đường chạy và cấp phát, không phải phân rã chi phí bằng profiling từng thay đổi.

| Benchmark và tham số | 0.5.0, microsecond | Trước khi sửa, microsecond | Sau khi sửa, microsecond | Thay đổi so với 0.5.0 | Ứng viên nguyên nhân |
|---|---:|---:|---:|---:|---|
| CaptureBenchmark `captureLogBatch (eventCount=100, mode=NONE)` | 3,731492 ± 0,019279 | 3,848917 ± 0,032839 | 3,871584 ± 0,044791 | +3,75% | Đường đọc cấu hình qua `ThreadMirror` và khác biệt biên dịch tối ưu là ứng viên; không có adapter nên không chạy lọc thuộc tính. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | 1,165974 ± 0,103124 | 2,437572 ± 0,085908 | 1,596855 ± 0,135608 | +36,95% | Chi phí dựng view và các cổng lười cho từng adapter còn lại từ các quyết định số 039, 040 và 044; không chạy walk hoặc tạo span record. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=ERROR)` | 1,126340 ± 0,102384 | 2,320683 ± 0,122519 | 1,446958 ± 0,137106 | +28,47% | Chi phí dựng view và các cổng lười cho từng adapter còn lại từ các quyết định số 039, 040 và 044; không chạy walk hoặc tạo span record. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=CANCELLED)` | 1,034864 ± 0,012921 | 2,235437 ± 0,205290 | 1,654155 ± 0,154742 | +59,84% | Chi phí dựng view và các cổng lười cho từng adapter còn lại từ các quyết định số 039, 040 và 044; không chạy walk hoặc tạo span record. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=CANCELLED)` | 68,962470 ± 4,229517 | 87,062880 ± 0,630619 | 77,366974 ± 0,936600 | +12,19% | View và record riêng cho từng adapter, bước lọc thuộc tính của quyết định số 039, cổng của quyết định số 040 và bộ máy duyệt mới; chưa tách riêng chi phí từng phần. |
| ReportScalingBenchmark `reportWarmMessages (shape=CHAIN, spanCount=512)` | 78,911334 ± 1,989666 | 95,982072 ± 1,080580 | 82,532000 ± 0,998806 | +4,59% | Bộ máy duyệt report còn giữ thứ tự span và offset; view lọc record và khác biệt tối ưu trên cây sâu là ứng viên. Tham chiếu chính tháng Mười chưa được đo bổ sung. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=16)` | 1,548521 ± 0,032191 | 2,218932 ± 0,032456 | 1,671383 ± 0,045597 | +7,93% | Danh sách thứ tự span, mảng offset và view của report vẫn có chi phí; lọc thuộc tính và khác biệt biên dịch tối ưu cũng là ứng viên. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=512)` | 57,996762 ± 0,708110 | 75,688557 ± 1,197494 | 59,830422 ± 0,601964 | +3,16% | Danh sách thứ tự span, mảng offset và view của report vẫn có chi phí; lọc thuộc tính và khác biệt biên dịch tối ưu cũng là ứng viên. |

Capture không sink không đi qua `attributeView` của
[quyết định số 039](../../../decisions/adr-039-adapter-accepts-attribute.md), cũng không dùng bộ phân giải
scope của [quyết định số 038](../../../decisions/adr-038-process-fallback-scope.md) trong `freshSpan`.
Chênh lệch là 0,140092 microsecond mỗi batch 100 log; đọc cấu hình qua `ThreadMirror` và thay đổi tối ưu mã là
ứng viên phù hợp hơn việc gán cho bộ lọc thuộc tính. Không có phép đo cô lập để chọn một nguyên nhân cụ thể.

Report bỏ qua với 16 adapter không chạy walk và không tạo span record. Cấp phát khoảng 10.056 byte sau khi
sửa, so với khoảng 6.976 byte ở 0.5.0, phù hợp với chi phí view tăng theo adapter. Trong bộ đo theo commit,
v0.6.0 đã dùng khoảng 10.048 byte, trước quyết định số 044. Điều này chỉ ra phần lớn mức tăng cấp phát còn
lại đã tồn tại trước quyết định số 044, dù chưa tách riêng được thời gian của từng cổng hoặc lambda.

Đường consume có view riêng cho mỗi adapter và bước `attributeView`, kể cả khi map thuộc tính rỗng và không
cần copy record. [Quyết định số 040](../../../decisions/adr-040-explicit-exception-origin.md) thêm cổng
exception tường minh; quyết định số 044 cùng bản sửa vẫn giữ thứ tự span và offset. Đó là các ứng viên cho
chi phí report còn lại. Không được quy toàn bộ mức tăng cho span record, vì cổng đó tắt trong mọi trường hợp đo.
Cây CHAIN 512 span còn dùng tham chiếu chính tháng Mười có sai số cấp phát lớn, nên ảnh hưởng của mức loại bỏ
cấp phát khi biên dịch cũng chưa được tách riêng.

## Cấp phát tăng so với 0.5.0

Theo cùng quy tắc khoảng, có **31 trường hợp tăng cấp phát, 40 trường hợp giảm và 19 trường hợp giao nhau**.
So với trước khi sửa, có bảy trường hợp tăng, 42 trường hợp giảm và 41 trường hợp giao nhau. Phân loại này
tách khỏi thời gian; tăng cấp phát không tự động là tăng độ trễ. Bảng sau liệt kê đủ 31 mức tăng so với 0.5.0,
kể cả mức tăng rất nhỏ do bộ đo. Bảng đầy đủ cuối tài liệu giữ khoảng của cả ba lần đo.

| Benchmark và tham số | Cấp phát tăng, byte mỗi operation | Thời gian so với 0.5.0 | Ứng viên giải thích |
|---|---:|---|---|
| CaptureBenchmark `captureLogBatch (eventCount=100, mode=NONE)` | 0,000989 | Chậm hơn | Tăng dưới một phần nghìn byte, phù hợp với phân bổ chi phí nền của bộ đo; không chứng minh thêm đối tượng. |
| ConcurrencyBenchmark `runWorkers (mode=BASELINE, workerCount=16)` | 426,443982 | Nhanh hơn | Không chạy kotrace; ứng viên là scheduling, coroutine và mức loại bỏ cấp phát của trình biên dịch. |
| ConcurrencyBenchmark `runWorkers (mode=SHARED_CAPPED, workerCount=16)` | 24053,653306 | Giao nhau | Lọc thuộc tính `kotrace.events_dropped` trên live record sau khi vượt giới hạn, theo quyết định số 039, là ứng viên trực tiếp; biên dịch tối ưu và cạnh tranh ghi chưa được tách riêng. |
| ReportFailureScalingBenchmark `reportExceptionBirthplaces (spanCount=16)` | 303,995381 | Nhanh hơn | Cấu trúc thứ tự span, offset và view report còn lại; thời gian vẫn giảm. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | 12477,333153 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=ERROR)` | 12466,664526 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=CANCELLED)` | 12487,996183 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=OK)` | 7,999708 | Giao nhau | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=ERROR)` | 7,999933 | Giao nhau | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=CANCELLED)` | 7,999947 | Giao nhau | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=OK)` | 4743,997149 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=ERROR)` | 4775,987114 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=CANCELLED)` | 4743,985798 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | 3080,002958 | Chậm hơn | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=ERROR)` | 3080,002178 | Chậm hơn | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=CANCELLED)` | 3080,004209 | Chậm hơn | Cấu trúc view và cổng lười cho từng adapter; không thể là cấp phát span record hoặc walk vì sequence không được đọc. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | 6591,993929 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=ERROR)` | 6591,981514 | Giao nhau | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportOutcomeBenchmark `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=CANCELLED)` | 6592,055089 | Chậm hơn | Record được dựng riêng theo từng adapter, view có bước lọc thuộc tính và cổng mới; chưa tách phần đối tượng thực sự khỏi khác biệt loại bỏ cấp phát. |
| ReportScalingBenchmark `reportWarmMessages (shape=CHAIN, spanCount=1)` | 63,999999 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=CHAIN, spanCount=16)` | 1381,334443 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=CHAIN, spanCount=128)` | 9736,005853 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=CHAIN, spanCount=512)` | 27636,851979 | Chậm hơn | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=STAR, spanCount=1)` | 114,666771 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=STAR, spanCount=16)` | 1717,333699 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=STAR, spanCount=128)` | 12496,002766 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=STAR, spanCount=512)` | 27466,633051 | Nhanh hơn | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=1)` | 192,000061 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=16)` | 1520,000786 | Chậm hơn | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=128)` | 9735,989950 | Giao nhau | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |
| ReportScalingBenchmark `reportWarmMessages (shape=BALANCED, spanCount=512)` | 4330,677693 | Chậm hơn | Thứ tự span, mảng offset và view report; mức loại bỏ cấp phát có thể khác giữa các fork. Tham chiếu không có follow-up còn chịu khác biệt timeline và snapshot tháng Mười. |

Đáng chú ý, `SHARED_CAPPED` với 16 worker tăng khoảng 24.054 byte nhưng thời gian có khoảng giao với 0.5.0.
Trường hợp concurrency `BASELINE` với 16 worker không gọi kotrace mà vẫn tăng cấp phát khoảng 426 byte;
điều đó cho thấy môi trường thực thi và biên dịch có thể làm thay đổi cấp phát. Capture 100 log không sink
tăng khoảng 0,000989 byte theo giá trị chuẩn hoá, không phải thêm một đối tượng mỗi batch.

## Concurrency, failure, lifecycle và các đường còn lại

Không có regression thời gian concurrency so với 0.5.0. Bảy trường hợp nhanh hơn rõ, năm trường hợp giao
nhau. `SHARED` với 16 worker nay giao khoảng với 0.5.0, còn `SHARED_CAPPED` với 16 worker có trung bình
tăng 5,36% nhưng sai số 38,486980 microsecond nên vẫn giao nhau. Không gọi mức tăng đó là regression.
Các mức giảm lớn ở những workload này không được gán cho sửa report, vì chúng không đi qua report adapter.

Cả ba concurrency `BASELINE`, vốn không dùng kotrace, chậm hơn rõ so với `20261102-full`. Vì vậy biến thiên
scheduling và môi trường là giới hạn thực tế khi đối chiếu concurrency giữa hai phiên. Không trừ hai trung
bình để tạo ra một khoảng tin cậy mới hoặc quy mọi thay đổi cho `ThreadMirror`.

Có đúng sáu trường hợp chậm hơn rõ so với lần trước khi sửa, liệt kê đầy đủ dưới đây. Ba trường hợp capture
không cập nhật lifecycle của span đã kết thúc hoặc chạy report; ba baseline concurrency không dùng kotrace.
Biên dịch tối ưu, bố trí mã và scheduling là các ứng viên, không có bằng chứng bản sửa report là nguyên nhân.

| Benchmark và tham số | Trước khi sửa, microsecond | Sau khi sửa, microsecond | Thay đổi |
|---|---:|---:|---:|
| CaptureBenchmark `captureLogBatch (eventCount=0, mode=LIVE_REJECT)` | 0,029589 ± 0,000128 | 0,030294 ± 0,000167 | +2,38% |
| CaptureBenchmark `captureLogBatch (eventCount=0, mode=LIVE_ACCEPT)` | 0,029646 ± 0,000096 | 0,030604 ± 0,000424 | +3,23% |
| CaptureBenchmark `captureLogBatch (eventCount=10, mode=NONE)` | 0,346325 ± 0,002530 | 0,362265 ± 0,003771 | +4,60% |
| ConcurrencyBenchmark `runWorkers (mode=BASELINE, workerCount=1)` | 6,820186 ± 0,217591 | 8,408778 ± 1,137424 | +23,29% |
| ConcurrencyBenchmark `runWorkers (mode=BASELINE, workerCount=4)` | 8,516038 ± 0,199788 | 9,796233 ± 0,641131 | +15,03% |
| ConcurrencyBenchmark `runWorkers (mode=BASELINE, workerCount=16)` | 12,377927 ± 0,273244 | 14,145399 ± 1,186957 | +14,28% |

Cả ba `tracedFailureClimb` giao khoảng với 0.5.0; độ sâu 32 nhanh hơn rõ lần trước khi sửa. Lifecycle không
có regression so với 0.5.0; chỉ `autoRootReportSkip` nhanh hơn rõ. Ba trường hợp giới hạn event, cả ba lần
duyệt snapshot và năm trong sáu trường hợp event-kind có khoảng giao với 0.5.0. Event-kind `NAMED` với
`LIVE_REJECT` nhanh hơn rõ. Live fan-out từ chối với một và bốn adapter nhanh hơn rõ; bốn trường hợp fan-out
còn lại giao nhau. Sensitive routing với `LIVE_ACCEPT`, tức policy chặn log sensitive, nhanh hơn rõ; với
`LIVE_SENSITIVE` thì giao nhau. Các tham chiếu chính tháng Mười chưa được đo bổ sung vẫn bao gồm khác biệt
timeline, snapshot và biên dịch tối ưu; không coi mọi cải thiện là đóng góp riêng của 0.7.0.

`FailureBenchmark` thực thi throw và catch; `ReportOutcomeBenchmark` chỉ truyền một giá trị `TraceStatus`
vào fixture dựng sẵn. Nhãn `CANCELLED` không phải một coroutine thực sự bị huỷ trong vùng đo, nên chênh lệch
giữa các trạng thái không chứng minh chi phí xử lý cancellation mới của quyết định số 044.

## Độ trễ live sink được giữ từ lần trước

**Không chạy lại `SinkLatencyBenchmark`.** Bản sửa không đổi đường giao record cho live sink, nên số liệu
ở `../20261102-full/sink-latency.json` đứng nguyên. Các giá trị sau được dẫn lại, không phải đo tại `d0d8ac6`:

| Số record | Phân vị thứ 50, millisecond mỗi flow | Phân vị thứ 99, millisecond mỗi flow |
|---:|---:|---:|
| 1 | 1,529856 | 2,817802 |
| 10 | 15,319040 | 21,821194 |

Khoảng thăm dò được tái dựng cho các phân vị, giới hạn của histogram và so sánh với tháng Chín nằm nguyên
trong [phân tích lần trước](../20261102-full/analysis.md#độ-trễ-của-live-sink-chặn-một-millisecond).
Không so sánh hai bản sao cùng một phép đo để kết luận có thay đổi. Đây là backpressure của sink đồng bộ
chặn một millisecond mỗi record trong harness, không phải độ trễ production dưới tải open-loop.

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
chênh lệch giữa hai cổng mới tách được chi phí tạo và giao span record khỏi chi phí duyệt và lọc mà adapter cũ
vẫn trả. Đây chỉ là đề xuất; không thêm hoặc sửa benchmark trong lần làm việc này.

## Giới hạn và sự cố

- Một lần chạy chính hoàn chỉnh, không bị dừng hoặc thay thế. Không có fork thất bại, timeout hoặc điểm số không hữu hạn. Preflight ban đầu dùng kết quả có sẵn, sau đó cả bảy kiểm thử được thực thi lại thành công.
- Chỉ có các lượt đọc log ngắn trong thời gian đo. Không chạy build, test hoặc lệnh phân tích nặng đồng thời. Môi trường chặn liệt kê tiến trình nên không thể xác nhận độc lập mọi ứng dụng khác đều nhàn rỗi; không tự đóng ứng dụng của người dùng.
- `RTK.md` không có trong repository. Gradle cảnh báo không khởi động được stream theo dõi hệ thống file; preflight đầu còn cảnh báo không ghi được thiết lập analytics Android. Truy vấn phần cứng bị chặn; chi tiết model, số lõi và bộ nhớ được kế thừa từ metadata cùng máy. Các cảnh báo không làm hỏng phép đo.
- Khoảng 99,9% là khoảng trong từng lần chạy; chưa điều chỉnh cho nhiều phép so sánh và không bao quát đầy đủ biến thiên giữa các phiên. Khác biệt rõ giữa phép đo bản sửa trước commit và lần đầy đủ cho thấy giới hạn này.
- Bộ dữ liệu theo commit chỉ có năm trường hợp report. Việc khoanh vùng quyết định số 044 không thay cho profiling hoặc đo từng thay đổi để phân rã tất cả chi phí so với 0.5.0. `fix.json` không phải chính lần đo tại commit đã tạo.
- Chỉ 12 trường hợp tháng Mười có phép đo bổ sung sau tối ưu timeline và snapshot. Những trường hợp dùng số liệu chính còn chịu khác biệt đó. Không gán toàn bộ cải thiện hoặc regression còn lại cho một quyết định kiến trúc.
- Không có ngân sách workload production để đặt ngưỡng đạt hoặc trượt. Mức tăng có khoảng không giao nhau vẫn có thể rất nhỏ về mặt thực tế. Cấp phát chuẩn hoá không phải bộ nhớ trace giữ lại.
- Phạm vi là core trên Java Virtual Machine HotSpot. Không đo Android ART, OkHttp network, Room database hoặc đường adapter nhận span record. Sink chỉ được dẫn lại từ lần trước.
- Mười một fingerprint nguồn không đổi; 196 file đã được theo dõi ngoài results và toàn bộ 15 file của `20261102-full` không đổi. Không chỉnh README, mã nguồn hoặc benchmark, không commit hoặc push.

## Đối chiếu đủ 90 trường hợp

Mỗi nhóm có bảng thời gian và bảng cấp phát. Cả hai đối chiếu 0.5.0, trước khi sửa và sau khi sửa, với trung
bình ± sai số khoảng tin cậy 99,9%. Thời gian là microsecond mỗi operation; cấp phát là byte mỗi operation.
Cột nguồn 0.5.0 thuộc thư mục `../20261017-full/`. Nguồn trước khi sửa luôn là `../20261102-full/main-gc.json`,
nguồn sau khi sửa là `main-gc.json` trong thư mục này. Kết luận dùng cận chưa làm tròn, không dùng số hiển thị.

### CaptureBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `captureLogBatch (eventCount=0, mode=NONE)` | `followup-capture.json` | 0,028036 ± 0,000502 | 0,028128 ± 0,000547 | 0,027918 ± 0,000377 | -0,42% | -0,75% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=0, mode=LIVE_REJECT)` | `main-gc.json` | 0,035245 ± 0,002746 | 0,029589 ± 0,000128 | 0,030294 ± 0,000167 | -14,05% | +2,38% | Nhanh hơn; chậm hơn |
| `captureLogBatch (eventCount=0, mode=LIVE_ACCEPT)` | `main-gc.json` | 0,035099 ± 0,002943 | 0,029646 ± 0,000096 | 0,030604 ± 0,000424 | -12,81% | +3,23% | Nhanh hơn; chậm hơn |
| `captureLogBatch (eventCount=10, mode=NONE)` | `main-gc.json` | 0,393269 ± 0,025198 | 0,346325 ± 0,002530 | 0,362265 ± 0,003771 | -7,88% | +4,60% | Nhanh hơn; chậm hơn |
| `captureLogBatch (eventCount=10, mode=LIVE_REJECT)` | `main-gc.json` | 0,408158 ± 0,017136 | 0,419524 ± 0,096250 | 0,387236 ± 0,012707 | -5,13% | -7,70% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=10, mode=LIVE_ACCEPT)` | `main-gc.json` | 0,656419 ± 0,051412 | 0,638168 ± 0,006831 | 0,646979 ± 0,014677 | -1,44% | +1,38% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=100, mode=NONE)` | `followup-capture.json` | 3,731492 ± 0,019279 | 3,848917 ± 0,032839 | 3,871584 ± 0,044791 | +3,75% | +0,59% | Chậm hơn; giao nhau |
| `captureLogBatch (eventCount=100, mode=LIVE_REJECT)` | `main-gc.json` | 4,050747 ± 0,049570 | 4,075942 ± 0,040513 | 4,081158 ± 0,139687 | +0,75% | +0,13% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=100, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,559846 ± 0,307815 | 6,453039 ± 0,175273 | 6,682322 ± 0,317271 | +1,87% | +3,55% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=1000, mode=NONE)` | `followup-capture.json` | 84,891542 ± 6,906190 | 81,496095 ± 0,799764 | 83,424947 ± 3,195053 | -1,73% | +2,37% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=1000, mode=LIVE_REJECT)` | `main-gc.json` | 85,688900 ± 2,282408 | 84,362168 ± 0,716123 | 86,099812 ± 4,492436 | +0,48% | +2,06% | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=1000, mode=LIVE_ACCEPT)` | `main-gc.json` | 120,036338 ± 6,716733 | 117,946842 ± 0,747799 | 129,571005 ± 24,086305 | +7,94% | +9,86% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `captureLogBatch (eventCount=0, mode=NONE)` | 328,000193 ± 0,000004 | 328,000194 ± 0,000004 | 328,000192 ± 0,000003 | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=0, mode=LIVE_REJECT)` | 384,000243 ± 0,000019 | 328,000203 ± 0,000002 | 328,000209 ± 0,000001 | Giảm; tăng |
| `captureLogBatch (eventCount=0, mode=LIVE_ACCEPT)` | 384,000242 ± 0,000020 | 328,000204 ± 0,000001 | 328,000211 ± 0,000003 | Giảm; tăng |
| `captureLogBatch (eventCount=10, mode=NONE)` | 1504,002709 ± 0,000173 | 1448,002376 ± 0,000020 | 1448,002495 ± 0,000027 | Giảm; tăng |
| `captureLogBatch (eventCount=10, mode=LIVE_REJECT)` | 1504,002814 ± 0,000118 | 1448,002885 ± 0,000665 | 1448,002665 ± 0,000090 | Giảm; giao nhau |
| `captureLogBatch (eventCount=10, mode=LIVE_ACCEPT)` | 3184,004520 ± 0,000345 | 3128,004397 ± 0,000047 | 3128,004457 ± 0,000101 | Giảm; giao nhau |
| `captureLogBatch (eventCount=100, mode=NONE)` | 29528,025709 ± 0,000116 | 29536,026515 ± 12,519524 | 29528,026699 ± 0,000356 | Tăng; giao nhau |
| `captureLogBatch (eventCount=100, mode=LIVE_REJECT)` | 29584,027982 ± 0,000375 | 29584,028089 ± 0,000290 | 29528,028134 ± 0,001012 | Giảm; giảm |
| `captureLogBatch (eventCount=100, mode=LIVE_ACCEPT)` | 46416,045211 ± 0,002151 | 46360,044437 ± 0,001347 | 46360,046122 ± 0,002237 | Giảm; giao nhau |
| `captureLogBatch (eventCount=1000, mode=NONE)` | 2092352,587497 ± 0,047719 | 2092352,562716 ± 0,004740 | 2092352,577966 ± 0,021946 | Giao nhau; giao nhau |
| `captureLogBatch (eventCount=1000, mode=LIVE_REJECT)` | 2092504,593722 ± 0,014491 | 2092448,582824 ± 0,005697 | 2092448,597196 ± 0,033222 | Giảm; giao nhau |
| `captureLogBatch (eventCount=1000, mode=LIVE_ACCEPT)` | 2267704,829464 ± 0,047103 | 2267648,813296 ± 0,006479 | 2267648,894699 ± 0,163571 | Giảm; giao nhau |

### ConcurrencyBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `runWorkers (mode=BASELINE, workerCount=1)` | `main-gc.json` | 11,022845 ± 1,730254 | 6,820186 ± 0,217591 | 8,408778 ± 1,137424 | -23,71% | +23,29% | Giao nhau; chậm hơn |
| `runWorkers (mode=BASELINE, workerCount=4)` | `main-gc.json` | 17,840585 ± 10,445483 | 8,516038 ± 0,199788 | 9,796233 ± 0,641131 | -45,09% | +15,03% | Giao nhau; chậm hơn |
| `runWorkers (mode=BASELINE, workerCount=16)` | `main-gc.json` | 17,678299 ± 1,847469 | 12,377927 ± 0,273244 | 14,145399 ± 1,186957 | -19,98% | +14,28% | Nhanh hơn; chậm hơn |
| `runWorkers (mode=SHARED, workerCount=1)` | `main-gc.json` | 23,251174 ± 2,065237 | 19,481470 ± 10,562198 | 18,169573 ± 1,751932 | -21,86% | -6,73% | Nhanh hơn; giao nhau |
| `runWorkers (mode=SHARED, workerCount=4)` | `main-gc.json` | 93,807076 ± 7,166175 | 78,118963 ± 4,500154 | 73,117880 ± 4,047708 | -22,06% | -6,40% | Nhanh hơn; giao nhau |
| `runWorkers (mode=SHARED, workerCount=16)` | `followup-conc.json` | 550,488900 ± 17,458404 | 601,113581 ± 20,846378 | 538,363136 ± 5,912425 | -2,20% | -10,44% | Giao nhau; nhanh hơn |
| `runWorkers (mode=SHARED_CAPPED, workerCount=1)` | `main-gc.json` | 24,610266 ± 2,890702 | 31,283872 ± 4,030529 | 19,733926 ± 1,312322 | -19,81% | -36,92% | Nhanh hơn; nhanh hơn |
| `runWorkers (mode=SHARED_CAPPED, workerCount=4)` | `main-gc.json` | 88,852915 ± 8,031618 | 105,043060 ± 1,088102 | 74,642923 ± 3,201589 | -15,99% | -28,94% | Nhanh hơn; nhanh hơn |
| `runWorkers (mode=SHARED_CAPPED, workerCount=16)` | `followup-conc.json` | 587,276154 ± 16,835599 | 645,225813 ± 6,034541 | 618,741660 ± 38,486980 | +5,36% | -4,10% | Giao nhau; giao nhau |
| `runWorkers (mode=CHILDREN, workerCount=1)` | `main-gc.json` | 21,080354 ± 1,217747 | 29,428981 ± 1,093632 | 19,299729 ± 0,911165 | -8,45% | -34,42% | Giao nhau; nhanh hơn |
| `runWorkers (mode=CHILDREN, workerCount=4)` | `main-gc.json` | 45,110533 ± 3,832703 | 50,132667 ± 0,514618 | 38,356255 ± 1,488372 | -14,97% | -23,49% | Nhanh hơn; nhanh hơn |
| `runWorkers (mode=CHILDREN, workerCount=16)` | `main-gc.json` | 133,043580 ± 7,177826 | 131,258665 ± 0,818365 | 113,444581 ± 9,102261 | -14,73% | -13,57% | Nhanh hơn; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `runWorkers (mode=BASELINE, workerCount=1)` | 1287,958598 ± 32,807969 | 1292,395215 ± 0,371595 | 1279,343287 ± 21,420295 | Giao nhau; giao nhau |
| `runWorkers (mode=BASELINE, workerCount=4)` | 2372,755986 ± 7,996802 | 2369,180679 ± 0,704174 | 2399,052836 ± 25,208643 | Giao nhau; tăng |
| `runWorkers (mode=BASELINE, workerCount=16)` | 6270,312652 ± 330,854145 | 6057,786895 ± 1,176088 | 6696,756634 ± 1,696089 | Tăng; tăng |
| `runWorkers (mode=SHARED, workerCount=1)` | 49293,573490 ± 24,962883 | 49210,583098 ± 9,187897 | 49202,692129 ± 8,425702 | Giảm; giao nhau |
| `runWorkers (mode=SHARED, workerCount=4)` | 431292,802186 ± 31,018939 | 431228,266207 ± 47,851862 | 431229,975592 ± 33,125639 | Giao nhau; giao nhau |
| `runWorkers (mode=SHARED, workerCount=16)` | 5557975,786680 ± 11,567707 | 5558026,405177 ± 17,753364 | 5557981,790118 ± 12,491726 | Giao nhau; giảm |
| `runWorkers (mode=SHARED_CAPPED, workerCount=1)` | 49299,631364 ± 16,831268 | 49237,718387 ± 26,881822 | 49218,688261 ± 27,489110 | Giảm; giao nhau |
| `runWorkers (mode=SHARED_CAPPED, workerCount=4)` | 431327,190320 ± 35,819624 | 431405,197208 ± 47,693746 | 431223,201600 ± 36,043092 | Giảm; giảm |
| `runWorkers (mode=SHARED_CAPPED, workerCount=16)` | 4888488,802318 ± 17,536644 | 4912565,801453 ± 10,567602 | 4912542,455624 ± 26,962942 | Tăng; giao nhau |
| `runWorkers (mode=CHILDREN, workerCount=1)` | 35808,050477 ± 20,355159 | 35714,391359 ± 25,865766 | 35723,477267 ± 25,306326 | Giảm; giao nhau |
| `runWorkers (mode=CHILDREN, workerCount=4)` | 132077,701762 ± 7,832020 | 131814,106547 ± 0,670031 | 131797,752589 ± 8,679699 | Giảm; giảm |
| `runWorkers (mode=CHILDREN, workerCount=16)` | 517286,886695 ± 7,746160 | 516344,175697 ± 3,064797 | 516343,484253 ± 8,942667 | Giảm; giao nhau |

### EventCapBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `captureCappedBatch (eventCount=2000, maxEvents=100)` | `followup-cap.json` | 142,324579 ± 30,025492 | 133,669574 ± 2,331491 | 130,203289 ± 3,191825 | -8,52% | -2,59% | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=1000)` | `followup-cap.json` | 311,680027 ± 7,841171 | 317,362146 ± 10,138043 | 307,471086 ± 4,960217 | -1,35% | -3,12% | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=10000)` | `followup-cap.json` | 330,473019 ± 18,194008 | 338,315502 ± 5,761610 | 321,406846 ± 4,298122 | -2,74% | -5,00% | Giao nhau; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `captureCappedBatch (eventCount=2000, maxEvents=100)` | 1048064,983962 ± 0,207193 | 1048064,923608 ± 0,017671 | 1048064,900410 ± 0,022415 | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=1000)` | 6228466,152045 ± 0,053233 | 6228466,193003 ± 0,071785 | 6228466,124220 ± 0,032812 | Giao nhau; giao nhau |
| `captureCappedBatch (eventCount=2000, maxEvents=10000)` | 8184354,282236 ± 0,122477 | 8184354,339269 ± 0,041244 | 8184354,222141 ± 0,032640 | Giao nhau; giảm |

### EventKindBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `capture100Events (kind=LOG, mode=LIVE_REJECT)` | `main-gc.json` | 5,019648 ± 0,185777 | 4,986858 ± 0,141059 | 4,837337 ± 0,054311 | -3,63% | -3,00% | Giao nhau; giao nhau |
| `capture100Events (kind=LOG, mode=LIVE_ACCEPT)` | `main-gc.json` | 7,751095 ± 0,607965 | 7,420018 ± 0,154975 | 7,407475 ± 0,095453 | -4,43% | -0,17% | Giao nhau; giao nhau |
| `capture100Events (kind=NAMED, mode=LIVE_REJECT)` | `main-gc.json` | 5,141218 ± 0,103632 | 4,792018 ± 0,049226 | 4,860666 ± 0,054277 | -5,46% | +1,43% | Nhanh hơn; giao nhau |
| `capture100Events (kind=NAMED, mode=LIVE_ACCEPT)` | `main-gc.json` | 7,055656 ± 0,438293 | 6,531365 ± 0,108959 | 6,688521 ± 0,073059 | -5,20% | +2,41% | Giao nhau; giao nhau |
| `capture100Events (kind=EXCEPTION, mode=LIVE_REJECT)` | `main-gc.json` | 4,894823 ± 0,123588 | 4,606561 ± 0,071152 | 4,696986 ± 0,093996 | -4,04% | +1,96% | Giao nhau; giao nhau |
| `capture100Events (kind=EXCEPTION, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,325575 ± 0,169148 | 6,152277 ± 0,176328 | 6,276629 ± 0,397020 | -0,77% | +2,02% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `capture100Events (kind=LOG, mode=LIVE_REJECT)` | 30312,034608 ± 0,001327 | 30266,701076 ± 16,692852 | 30256,033359 ± 0,000374 | Giảm; giao nhau |
| `capture100Events (kind=LOG, mode=LIVE_ACCEPT)` | 47133,386837 ± 16,691666 | 47066,717783 ± 16,692478 | 47056,051114 ± 0,000681 | Giảm; giao nhau |
| `capture100Events (kind=NAMED, mode=LIVE_REJECT)` | 31112,035443 ± 0,000765 | 31056,033011 ± 0,000353 | 31056,033497 ± 0,000404 | Giảm; giao nhau |
| `capture100Events (kind=NAMED, mode=LIVE_ACCEPT)` | 42344,048667 ± 0,003027 | 42256,045054 ± 0,000804 | 42256,046144 ± 0,000509 | Giảm; giao nhau |
| `capture100Events (kind=EXCEPTION, mode=LIVE_REJECT)` | 27912,033753 ± 0,000872 | 27856,031724 ± 0,000515 | 27856,032390 ± 0,000676 | Giảm; giao nhau |
| `capture100Events (kind=EXCEPTION, mode=LIVE_ACCEPT)` | 39133,376973 ± 16,692451 | 39088,042417 ± 0,001292 | 39066,709972 ± 16,694851 | Giảm; giảm |

### EventReadBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `traverseSnapshot (eventCount=10)` | `main-gc.json` | 0,008594 ± 0,000135 | 0,008186 ± 0,000102 | 0,008358 ± 0,000131 | -2,74% | +2,10% | Giao nhau; giao nhau |
| `traverseSnapshot (eventCount=100)` | `main-gc.json` | 0,051828 ± 0,001433 | 0,049957 ± 0,001000 | 0,051205 ± 0,000926 | -1,20% | +2,50% | Giao nhau; giao nhau |
| `traverseSnapshot (eventCount=1000)` | `main-gc.json` | 0,514239 ± 0,018098 | 0,502368 ± 0,018861 | 0,494512 ± 0,014309 | -3,84% | -1,56% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `traverseSnapshot (eventCount=10)` | 0,000059 ± 0,000001 | 0,000056 ± 0,000001 | 0,000057 ± 0,000001 | Giao nhau; giao nhau |
| `traverseSnapshot (eventCount=100)` | 0,000355 ± 0,000010 | 0,000342 ± 0,000007 | 0,000350 ± 0,000007 | Giao nhau; giao nhau |
| `traverseSnapshot (eventCount=1000)` | 48,003526 ± 0,000128 | 48,003444 ± 0,000133 | 48,003390 ± 0,000100 | Giao nhau; giao nhau |

### FailureBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `coroutineThrowBaseline (depth=1)` | `main-gc.json` | 0,961629 ± 0,039612 | 0,958273 ± 0,022883 | 0,935421 ± 0,012534 | -2,73% | -2,38% | Giao nhau; giao nhau |
| `coroutineThrowBaseline (depth=8)` | `main-gc.json` | 5,549397 ± 0,165464 | 5,179225 ± 0,045257 | 5,177151 ± 0,079184 | -6,71% | -0,04% | Nhanh hơn; giao nhau |
| `coroutineThrowBaseline (depth=32)` | `main-gc.json` | 22,942050 ± 0,923655 | 21,564168 ± 0,364862 | 21,692175 ± 0,340615 | -5,45% | +0,59% | Giao nhau; giao nhau |
| `tracedFailureClimb (depth=1)` | `main-gc.json` | 3,670519 ± 0,295899 | 3,562608 ± 0,292554 | 3,405775 ± 0,092307 | -7,21% | -4,40% | Giao nhau; giao nhau |
| `tracedFailureClimb (depth=8)` | `main-gc.json` | 14,387968 ± 1,727328 | 13,950656 ± 0,375757 | 13,232793 ± 0,720919 | -8,03% | -5,15% | Giao nhau; giao nhau |
| `tracedFailureClimb (depth=32)` | `main-gc.json` | 47,498657 ± 2,716341 | 47,624151 ± 0,657180 | 45,423149 ± 1,474992 | -4,37% | -4,62% | Giao nhau; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `coroutineThrowBaseline (depth=1)` | 944,006628 ± 0,000287 | 960,006602 ± 25,039188 | 944,006446 ± 0,000087 | Giao nhau; giao nhau |
| `coroutineThrowBaseline (depth=8)` | 6824,038259 ± 25,039774 | 6840,035643 ± 0,000307 | 6768,035695 ± 78,184937 | Giao nhau; giao nhau |
| `coroutineThrowBaseline (depth=32)` | 26920,158556 ± 0,006244 | 26920,148842 ± 0,002822 | 26904,149714 ± 25,038543 | Giao nhau; giao nhau |
| `tracedFailureClimb (depth=1)` | 4472,054069 ± 0,055544 | 4416,074008 ± 0,088058 | 4400,071639 ± 25,039709 | Giảm; giao nhau |
| `tracedFailureClimb (depth=8)` | 20608,192243 ± 0,205481 | 20192,186124 ± 25,037830 | 20176,176146 ± 25,036989 | Giảm; giao nhau |
| `tracedFailureClimb (depth=32)` | 77840,481943 ± 24,925108 | 76048,643816 ± 25,054215 | 76064,168418 ± 0,325063 | Giảm; giao nhau |

### LifecycleBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `autoRootEmptyConfig` | `main-gc.json` | 1,672742 ± 0,034703 | 1,626000 ± 0,022574 | 1,674263 ± 0,065256 | +0,09% | +2,97% | Giao nhau; giao nhau |
| `autoRootLiveAdapter` | `main-gc.json` | 1,716560 ± 0,103833 | 1,601284 ± 0,052475 | 1,583297 ± 0,046187 | -7,76% | -1,12% | Giao nhau; giao nhau |
| `autoRootNoConfig` | `followup-life.json` | 1,008260 ± 0,022757 | 0,992373 ± 0,017593 | 0,970507 ± 0,018683 | -3,74% | -2,20% | Giao nhau; giao nhau |
| `autoRootReportSkip` | `main-gc.json` | 1,780909 ± 0,046624 | 1,665388 ± 0,036158 | 1,650536 ± 0,038504 | -7,32% | -0,89% | Nhanh hơn; giao nhau |
| `configuredCoroutineBaseline` | `main-gc.json` | 0,414416 ± 0,055932 | 0,386253 ± 0,065535 | 0,361527 ± 0,058105 | -12,76% | -6,40% | Giao nhau; giao nhau |
| `coroutineBaseline` | `main-gc.json` | 0,081611 ± 0,008278 | 0,079359 ± 0,001871 | 0,080065 ± 0,000881 | -1,89% | +0,89% | Giao nhau; giao nhau |
| `plain` | `main-gc.json` | 0,000613 ± 0,000037 | 0,000615 ± 0,000064 | 0,000589 ± 0,000034 | -3,98% | -4,28% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `autoRootEmptyConfig` | 2640,020744 ± 0,029975 | 2584,013160 ± 0,000158 | 2616,013482 ± 25,039446 | Giao nhau; tăng |
| `autoRootLiveAdapter` | 2640,028452 ± 0,041328 | 2600,012981 ± 25,039472 | 2600,012867 ± 25,039429 | Giảm; giao nhau |
| `autoRootNoConfig` | 2165,342231 ± 20,865925 | 2152,008794 ± 0,000121 | 2152,008638 ± 0,000136 | Giao nhau; giao nhau |
| `autoRootReportSkip` | 2872,037304 ± 0,051000 | 2856,013412 ± 25,039255 | 2832,013289 ± 25,039097 | Giảm; giao nhau |
| `configuredCoroutineBaseline` | 512,002857 ± 0,000389 | 528,002658 ± 25,039054 | 512,002478 ± 0,000397 | Giao nhau; giao nhau |
| `coroutineBaseline` | 234,667229 ± 20,865928 | 208,000547 ± 0,000014 | 208,000550 ± 0,000007 | Giảm; giao nhau |
| `plain` | 0,000004 ± 0,000000 | 0,000004 ± 0,000000 | 0,000004 ± 0,000000 | Giao nhau; giao nhau |

### LiveFanoutBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `fanOut100Logs (adapterCount=1, mode=LIVE_REJECT)` | `main-gc.json` | 4,526230 ± 0,456302 | 4,188715 ± 0,228855 | 3,972290 ± 0,049339 | -12,24% | -5,17% | Nhanh hơn; giao nhau |
| `fanOut100Logs (adapterCount=1, mode=LIVE_ACCEPT)` | `main-gc.json` | 6,767444 ± 0,187329 | 6,564904 ± 0,122531 | 6,485278 ± 0,186753 | -4,17% | -1,21% | Giao nhau; giao nhau |
| `fanOut100Logs (adapterCount=4, mode=LIVE_REJECT)` | `main-gc.json` | 4,829820 ± 0,405423 | 4,402235 ± 0,178013 | 4,221213 ± 0,014524 | -12,60% | -4,11% | Nhanh hơn; giao nhau |
| `fanOut100Logs (adapterCount=4, mode=LIVE_ACCEPT)` | `main-gc.json` | 12,633903 ± 4,985926 | 10,154695 ± 0,330212 | 9,839397 ± 1,163273 | -22,12% | -3,10% | Giao nhau; giao nhau |
| `fanOut100Logs (adapterCount=16, mode=LIVE_REJECT)` | `main-gc.json` | 6,123665 ± 1,329656 | 5,269389 ± 0,272191 | 4,984926 ± 0,219797 | -18,60% | -5,40% | Giao nhau; giao nhau |
| `fanOut100Logs (adapterCount=16, mode=LIVE_ACCEPT)` | `main-gc.json` | 32,889496 ± 3,939158 | 29,523122 ± 0,609871 | 28,952435 ± 0,575537 | -11,97% | -1,93% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `fanOut100Logs (adapterCount=1, mode=LIVE_REJECT)` | 29584,031203 ± 0,003178 | 29546,695500 ± 29,211424 | 29584,027283 ± 0,000338 | Giảm; tăng |
| `fanOut100Logs (adapterCount=1, mode=LIVE_ACCEPT)` | 46405,380013 ± 16,693438 | 46360,045274 ± 0,000890 | 46346,711218 ± 29,212416 | Giảm; giao nhau |
| `fanOut100Logs (adapterCount=4, mode=LIVE_REJECT)` | 29584,033312 ± 0,002850 | 29528,030337 ± 0,001237 | 29528,028943 ± 0,000153 | Giảm; giảm |
| `fanOut100Logs (adapterCount=4, mode=LIVE_ACCEPT)` | 46408,087166 ± 0,034321 | 46360,070121 ± 0,002382 | 46352,067600 ± 0,007930 | Giảm; giảm |
| `fanOut100Logs (adapterCount=16, mode=LIVE_REJECT)` | 29584,042229 ± 0,009155 | 29528,036360 ± 0,001835 | 29528,034383 ± 0,001533 | Giảm; giao nhau |
| `fanOut100Logs (adapterCount=16, mode=LIVE_ACCEPT)` | 64829,560453 ± 16,692955 | 64760,203959 ± 0,004396 | 64752,199817 ± 0,004068 | Giảm; giảm |

### ReportFailureScalingBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `reportExceptionBirthplaces (spanCount=16)` | `main-gc.json` | 2,948278 ± 0,450074 | 2,963041 ± 0,096148 | 2,280901 ± 0,049181 | -22,64% | -23,02% | Nhanh hơn; nhanh hơn |
| `reportExceptionBirthplaces (spanCount=128)` | `main-gc.json` | 27,592680 ± 1,558264 | 26,288878 ± 0,487412 | 21,615209 ± 0,946970 | -21,66% | -17,78% | Nhanh hơn; nhanh hơn |
| `reportExceptionBirthplaces (spanCount=512)` | `main-gc.json` | 134,647634 ± 9,213463 | 117,094383 ± 3,423965 | 108,385691 ± 2,994091 | -19,50% | -7,44% | Nhanh hơn; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `reportExceptionBirthplaces (spanCount=16)` | 13320,020325 ± 0,003109 | 13736,020411 ± 0,000665 | 13624,015707 ± 0,000334 | Tăng; giảm |
| `reportExceptionBirthplaces (spanCount=128)` | 117141,523625 ± 3714,140263 | 118261,514946 ± 16,690477 | 112856,149170 ± 0,006784 | Giảm; giảm |
| `reportExceptionBirthplaces (spanCount=512)` | 485328,931704 ± 0,064278 | 475240,808234 ± 0,024691 | 448624,748775 ± 0,020809 | Giảm; giảm |

### ReportOutcomeBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,014456 ± 0,113659 | 1,494937 ± 0,018340 | 0,948504 ± 0,026027 | -6,50% | -36,55% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 0,991746 ± 0,043796 | 1,508097 ± 0,011982 | 0,951884 ± 0,117781 | -4,02% | -36,88% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 0,986713 ± 0,029357 | 1,514476 ± 0,039554 | 0,901275 ± 0,010919 | -8,66% | -40,49% | Nhanh hơn; nhanh hơn |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 13,127824 ± 1,067989 | 17,395958 ± 0,819407 | 13,103824 ± 0,159250 | -0,18% | -24,67% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 13,476185 ± 1,250169 | 18,224404 ± 0,972173 | 13,163231 ± 0,087671 | -2,32% | -27,77% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 13,651577 ± 0,736009 | 17,580602 ± 0,424635 | 13,110949 ± 0,126625 | -3,96% | -25,42% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,038587 ± 0,070977 | 1,641553 ± 0,036612 | 0,997454 ± 0,011641 | -3,96% | -39,24% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 1,012589 ± 0,016265 | 1,665063 ± 0,064639 | 1,001981 ± 0,010398 | -1,05% | -39,82% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 0,999960 ± 0,112905 | 1,682792 ± 0,038946 | 0,994068 ± 0,014228 | -0,59% | -40,93% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 26,547072 ± 0,652739 | 32,141389 ± 1,801757 | 26,132708 ± 0,217138 | -1,56% | -18,69% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 28,326002 ± 2,630900 | 44,391672 ± 16,731069 | 26,557121 ± 0,753711 | -6,24% | -40,18% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 27,842010 ± 2,552975 | 31,480071 ± 0,825850 | 25,906772 ± 0,280110 | -6,95% | -17,70% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | `main-gc.json` | 1,165974 ± 0,103124 | 2,437572 ± 0,085908 | 1,596855 ± 0,135608 | +36,95% | -34,49% | Chậm hơn; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=ERROR)` | `main-gc.json` | 1,126340 ± 0,102384 | 2,320683 ± 0,122519 | 1,446958 ± 0,137106 | +28,47% | -37,65% | Chậm hơn; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=CANCELLED)` | `main-gc.json` | 1,034864 ± 0,012921 | 2,235437 ± 0,205290 | 1,654155 ± 0,154742 | +59,84% | -26,00% | Chậm hơn; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | `main-gc.json` | 78,663791 ± 13,948554 | 91,134943 ± 4,406453 | 77,928298 ± 1,056532 | -0,93% | -14,49% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=ERROR)` | `main-gc.json` | 78,953174 ± 12,998567 | 88,889734 ± 2,639901 | 76,336066 ± 1,487251 | -3,31% | -14,12% | Giao nhau; nhanh hơn |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=CANCELLED)` | `main-gc.json` | 68,962470 ± 4,229517 | 87,062880 ± 0,630619 | 77,366974 ± 0,936600 | +12,19% | -11,14% | Chậm hơn; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=OK)` | 6376,006999 ± 0,000787 | 6496,010301 ± 0,000132 | 5498,673204 ± 91,810127 | Giảm; giảm |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=ERROR)` | 6376,006846 ± 0,000297 | 6496,010388 ± 0,000091 | 5440,006558 ± 0,000822 | Giảm; giảm |
| `reportByOutcome (adapterCount=1, mode=REPORT_SKIP, status=CANCELLED)` | 6376,006818 ± 0,000210 | 6496,010442 ± 0,000274 | 5440,006210 ± 0,000078 | Giảm; giảm |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=OK)` | 38706,757262 ± 16,690951 | 56600,120091 ± 12,516806 | 51184,090415 ± 0,001156 | Tăng; giảm |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=ERROR)` | 38717,426357 ± 16,693600 | 56608,125836 ± 0,006562 | 51184,090883 ± 0,000638 | Tăng; giảm |
| `reportByOutcome (adapterCount=1, mode=REPORT_CONSUME, status=CANCELLED)` | 38696,094302 ± 0,005017 | 56608,121522 ± 0,002958 | 51184,090485 ± 0,000814 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=OK)` | 6496,007165 ± 0,000482 | 7464,011314 ± 0,000244 | 6504,006873 ± 0,000079 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=ERROR)` | 6496,006978 ± 0,000109 | 7464,011479 ± 0,000456 | 6504,006911 ± 0,000075 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_SKIP, status=CANCELLED)` | 6496,006897 ± 0,000779 | 7464,011587 ± 0,000265 | 6504,006845 ± 0,000099 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=OK)` | 72360,183400 ± 0,004797 | 82344,222231 ± 12,518267 | 77104,180549 ± 0,001820 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=ERROR)` | 72328,195664 ± 50,084733 | 82336,306806 ± 0,115856 | 77104,182778 ± 0,005118 | Tăng; giảm |
| `reportByOutcome (adapterCount=4, mode=REPORT_CONSUME, status=CANCELLED)` | 72360,192424 ± 0,017461 | 82336,217608 ± 0,005619 | 77104,178222 ± 0,002025 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=OK)` | 6976,008034 ± 0,000692 | 10728,016819 ± 0,000613 | 10056,010992 ± 0,000938 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=ERROR)` | 6976,007767 ± 0,000701 | 10728,016013 ± 0,000853 | 10056,009945 ± 0,000951 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_SKIP, status=CANCELLED)` | 6976,007145 ± 0,000091 | 10728,015410 ± 0,001417 | 10056,011354 ± 0,001066 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=OK)` | 174216,544875 ± 0,096778 | 185248,631640 ± 0,030842 | 180808,538804 ± 0,009463 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=ERROR)` | 174216,546040 ± 0,088209 | 185248,615429 ± 0,017856 | 180808,527555 ± 0,010872 | Tăng; giảm |
| `reportByOutcome (adapterCount=16, mode=REPORT_CONSUME, status=CANCELLED)` | 174216,478397 ± 0,028700 | 185264,603653 ± 12,519120 | 180808,533486 ± 0,007961 | Tăng; giảm |

### ReportScalingBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `reportWarmMessages (shape=CHAIN, spanCount=1)` | `main-gc.json` | 0,143420 ± 0,004134 | 0,179862 ± 0,002861 | 0,143956 ± 0,002690 | +0,37% | -19,96% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=CHAIN, spanCount=16)` | `main-gc.json` | 1,753135 ± 0,181475 | 2,392312 ± 0,050234 | 1,918678 ± 0,028825 | +9,44% | -19,80% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=CHAIN, spanCount=128)` | `main-gc.json` | 16,087482 ± 1,562587 | 21,079339 ± 0,355401 | 17,019832 ± 0,725662 | +5,80% | -19,26% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=CHAIN, spanCount=512)` | `main-gc.json` | 78,911334 ± 1,989666 | 95,982072 ± 1,080580 | 82,532000 ± 0,998806 | +4,59% | -14,01% | Chậm hơn; nhanh hơn |
| `reportWarmMessages (shape=STAR, spanCount=1)` | `main-gc.json` | 0,124639 ± 0,020433 | 0,180870 ± 0,001206 | 0,140193 ± 0,000691 | +12,48% | -22,49% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=STAR, spanCount=16)` | `main-gc.json` | 1,434824 ± 0,057897 | 2,119688 ± 0,071418 | 1,495533 ± 0,043088 | +4,23% | -29,45% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=STAR, spanCount=128)` | `main-gc.json` | 11,121179 ± 0,506050 | 17,003727 ± 0,523897 | 11,588613 ± 0,134270 | +4,20% | -31,85% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=STAR, spanCount=512)` | `main-gc.json` | 59,318103 ± 2,847851 | 73,870980 ± 1,237587 | 54,791616 ± 0,532256 | -7,63% | -25,83% | Nhanh hơn; nhanh hơn |
| `reportWarmMessages (shape=BALANCED, spanCount=1)` | `followup-report.json` | 0,131991 ± 0,008121 | 0,175429 ± 0,000753 | 0,141736 ± 0,002296 | +7,38% | -19,21% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=BALANCED, spanCount=16)` | `followup-report.json` | 1,548521 ± 0,032191 | 2,218932 ± 0,032456 | 1,671383 ± 0,045597 | +7,93% | -24,68% | Chậm hơn; nhanh hơn |
| `reportWarmMessages (shape=BALANCED, spanCount=128)` | `main-gc.json` | 14,193102 ± 1,452204 | 16,351168 ± 0,416689 | 12,790668 ± 0,254206 | -9,88% | -21,78% | Giao nhau; nhanh hơn |
| `reportWarmMessages (shape=BALANCED, spanCount=512)` | `followup-report.json` | 57,996762 ± 0,708110 | 75,688557 ± 1,197494 | 59,830422 ± 0,601964 | +3,16% | -20,95% | Chậm hơn; nhanh hơn |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `reportWarmMessages (shape=CHAIN, spanCount=1)` | 744,000990 ± 12,519574 | 856,001238 ± 0,000019 | 808,000989 ± 0,000018 | Tăng; giảm |
| `reportWarmMessages (shape=CHAIN, spanCount=16)` | 7122,678761 ± 523,748262 | 8528,016489 ± 0,000367 | 8504,013204 ± 12,519679 | Tăng; giảm |
| `reportWarmMessages (shape=CHAIN, spanCount=128)` | 52536,111217 ± 4281,722462 | 67688,145462 ± 12,521783 | 62272,117070 ± 0,004936 | Tăng; giảm |
| `reportWarmMessages (shape=CHAIN, spanCount=512)` | 217483,716312 ± 16986,839916 | 271728,664285 ± 12,520406 | 245120,568291 ± 0,008019 | Tăng; giảm |
| `reportWarmMessages (shape=STAR, spanCount=1)` | 693,334193 ± 66,771106 | 856,001246 ± 0,000009 | 808,000964 ± 0,000006 | Tăng; giảm |
| `reportWarmMessages (shape=STAR, spanCount=16)` | 4394,676565 ± 15,046685 | 6144,014546 ± 0,000488 | 6112,010264 ± 0,000298 | Tăng; giảm |
| `reportWarmMessages (shape=STAR, spanCount=128)` | 29976,076914 ± 0,003533 | 47856,116920 ± 0,003920 | 42472,079681 ± 12,519524 | Tăng; giảm |
| `reportWarmMessages (shape=STAR, spanCount=512)` | 135645,744043 ± 17105,885648 | 189728,509885 ± 0,009548 | 163112,377095 ± 0,005762 | Tăng; giảm |
| `reportWarmMessages (shape=BALANCED, spanCount=1)` | 616,000911 ± 25,039146 | 856,001204 ± 0,000005 | 808,000971 ± 0,000015 | Tăng; giảm |
| `reportWarmMessages (shape=BALANCED, spanCount=16)` | 5760,010678 ± 200,313005 | 7296,015217 ± 0,000219 | 7280,011464 ± 12,519730 | Tăng; giảm |
| `reportWarmMessages (shape=BALANCED, spanCount=128)` | 41448,097973 ± 4281,712268 | 56608,112565 ± 0,003053 | 51184,087923 ± 0,001752 | Tăng; giảm |
| `reportWarmMessages (shape=BALANCED, spanCount=512)` | 195936,401994 ± 0,005546 | 226856,521615 ± 0,008940 | 200267,079687 ± 20,868579 | Tăng; giảm |

### SensitiveRoutingBenchmark

Thời gian:

| Phương thức và tham số | Nguồn 0.5.0 | 0.5.0 | Trước khi sửa | Sau khi sửa | Thay đổi so với 0.5.0 | Thay đổi so với trước khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---|---:|---:|---:|---:|---:|---|
| `route100SensitiveLogs (mode=LIVE_ACCEPT)` | `main-gc.json` | 4,413962 ± 0,259443 | 3,969665 ± 0,021355 | 3,943128 ± 0,050316 | -10,67% | -0,67% | Nhanh hơn; giao nhau |
| `route100SensitiveLogs (mode=LIVE_SENSITIVE)` | `main-gc.json` | 6,768822 ± 0,172343 | 6,415473 ± 0,262379 | 6,584318 ± 0,204623 | -2,73% | +2,63% | Giao nhau; giao nhau |

Cấp phát:

| Phương thức và tham số | 0.5.0 | Trước khi sửa | Sau khi sửa | Nhận định so với 0.5.0; trước khi sửa |
|---|---:|---:|---:|---|
| `route100SensitiveLogs (mode=LIVE_ACCEPT)` | 29584,030387 ± 0,001628 | 29592,027235 ± 50,078255 | 29549,360375 ± 16,693092 | Giảm; giao nhau |
| `route100SensitiveLogs (mode=LIVE_SENSITIVE)` | 46405,380048 ± 16,692487 | 46360,043968 ± 0,001856 | 46360,045195 ± 0,001461 | Giảm; giao nhau |
