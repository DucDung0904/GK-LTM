# Giải thích toàn bộ dự án: UDP Exchange Rate System

Dự án này là một ứng dụng Client-Server sử dụng giao thức **UDP (User Datagram Protocol)** kết hợp với cơ chế **Unicast** để truyền tải dữ liệu tỷ giá ngoại tệ giả lập theo thời gian thực. 

Ngôn ngữ sử dụng: **Java** (Core Java cho mạng và Swing cho giao diện người dùng).

---

## 1. Kiến trúc tổng quan
- **Mô hình**: Client-Server.
- **Giao thức**: UDP. Mặc dù UDP không đảm bảo độ tin cậy nhưng nó nhanh, phù hợp cho việc truyền tải dữ liệu thời gian thực (như tỷ giá, giá cổ phiếu) nơi mà tốc độ quan trọng hơn việc mất đi 1-2 bản tin cũ.
- **Cơ chế Publish-Subscribe**: Các Client muốn nhận dữ liệu phải gửi một thông điệp `SUBSCRIBE` đến Server. Server sẽ lưu địa chỉ IP và Port của các Client này lại. Khi có dữ liệu tỷ giá mới, Server sẽ duyệt qua danh sách các Client đã đăng ký và gửi tin nhắn cập nhật tỷ giá trực tiếp đến từng Client (cơ chế Unicast). Khi Client thoát, nó gửi tin `UNSUBSCRIBE` để Server xóa khỏi danh sách.

---

## 2. Cấu trúc mã nguồn

Dự án nằm trong package `udpgk` và được chia thành 2 thư mục chính: `client` và `server`.

### 2.1. udpgk.server.Constants (Cấu hình chung)
Đây là một lớp tiện ích chỉ chứa các hằng số cấu hình dùng chung cho cả Client và Server để đảm bảo tính nhất quán:
- `SERVER_PORT = 9999`: Cổng mạng (Port) mà Server sẽ lắng nghe.
- `SERVER_HOST`: Địa chỉ IP của Server.
- `SUBSCRIBE` / `UNSUBSCRIBE`: Các chuỗi lệnh (command) mà Client gửi lên Server để đăng ký hoặc hủy đăng ký nhận dữ liệu.
- Các thiết lập về kích thước bộ nhớ đệm (buffer) và số dòng tối đa trên bảng UI.

### 2.2. udpgk.server.RateRecord (Mô hình dữ liệu - Payload)
Lớp này đại diện cho một "bản tin tỷ giá" tại một thời điểm nhất định.
- **Thuộc tính**: Chứa thời gian (`time`) và tỷ giá của 3 thị trường: Tokyo, Paris, Seoul.
- **Tạo dữ liệu ngẫu nhiên**: Có phương thức `randomSnapshot()` để tạo ra tỷ giá ngẫu nhiên giả lập (sử dụng `ThreadLocalRandom`).
- **Chuẩn hóa (Serialize) & Giải mã (Parse)**: Thay vì dùng JSON cồng kềnh, payload được chuyển thành một chuỗi ngắn gọn ngăn cách bởi dấu `;` (Ví dụ: `10:15:30;120.50;110.20;1050.00`). Điều này giúp **tối ưu hóa băng thông mạng**. Phương thức `parse` giúp dịch ngược chuỗi này thành đối tượng `RateRecord`.

### 2.3. udpgk.server.ExchangeRateServer (Máy chủ UDP)
Máy chủ hoạt động với 2 luồng (Thread) chạy song song:
1. **Luồng nhận (Receiver Thread)**: Liên tục lắng nghe gói tin (DatagramPacket) gửi đến cổng `9999`. 
   - Nếu nội dung là `SUBSCRIBE`, nó sẽ thêm thông tin `InetSocketAddress` (gồm IP và Port) của Client vào tập hợp `clients` (sử dụng `ConcurrentHashMap.newKeySet()` để thread-safe).
   - Nếu là `UNSUBSCRIBE`, nó xóa Client đó khỏi danh sách.
2. **Luồng gửi (Sender Thread)**: Vòng lặp liên tục tạo ra một `RateRecord` mới mỗi giây. Nó Serialize dữ liệu này thành mảng byte. Sau đó lặp qua danh sách toàn bộ các Client đang đăng ký và gửi bản tin UDP (DatagramPacket) chứa thông tin tỷ giá đến từng Client. (Ở đây đã có bước tối ưu hóa việc tạo đối tượng `DatagramPacket`).

### 2.4. udpgk.client.ExchangeRateClient (Máy khách và Giao diện)
Client là một ứng dụng giao diện cửa sổ xây dựng bằng Java Swing.
- **Giao diện (UI)**: Gồm một bảng `JTable` hiển thị lịch sử các lần tỷ giá cập nhật và các nút điều khiển (Pause, Clear).
- **Kết nối Server**: Khi khởi động, Client tạo một `DatagramSocket` ở một Port ngẫu nhiên do HĐH cấp và gửi ngay thông điệp `SUBSCRIBE` đến IP/Port của Server.
- **Luồng nhận (Receiver Thread)**: Client tạo một luồng riêng liên tục gọi `socket.receive(...)` để nhận các gói tin từ Server. Khi nhận được chuỗi tỷ giá, nó sẽ dùng `RateRecord.parse(...)` để chuyển thành đối tượng và cập nhật lên bảng (thông qua `SwingUtilities.invokeLater` để đảm bảo an toàn cho UI Thread).
- **Đóng ứng dụng**: Khi người dùng đóng cửa sổ, Client gửi thông điệp `UNSUBSCRIBE` tới Server để báo hiệu ngừng nhận, đồng thời đóng socket gọn gàng.

---

## 3. Tổng kết luồng hoạt động (Workflow)

1. **Khởi động**: Bật `ExchangeRateServer`. Nó bắt đầu lắng nghe ở Port 9999. Luồng tạo tỷ giá được chạy nhưng chưa gửi đi vì chưa có ai nhận.
2. **Kết nối**: Bật `ExchangeRateClient`. Nó gửi gói `SUBSCRIBE` -> `9999`. Server nhận được và lưu Client A vào bộ nhớ.
3. **Phân phối (Broadcast / Unicast)**: Mỗi giây, Server sinh ra tỷ giá mới và gửi gói tin UDP đến địa chỉ của Client A.
4. **Hiển thị**: Client A nhận gói tin, phân tích dữ liệu, hiển thị hàng mới lên bảng Giao diện. 
5. **Ngắt kết nối**: Khi tắt Client A, gói `UNSUBSCRIBE` được gửi lên Server. Server loại bỏ Client A. Server ngừng gửi tin đến Client A.
