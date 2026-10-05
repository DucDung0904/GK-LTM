# UDP Unicast Exchange Rate

Ứng dụng Java mô phỏng hệ thống lấy tỷ giá theo mô hình **Client–Server**, sử dụng giao thức **UDP Unicast**.

Server sinh tỷ giá ngẫu nhiên cho ba thị trường Tokyo, Paris và Seoul mỗi giây. Mỗi client đăng ký bằng lệnh `SUBSCRIBE`, sau đó server gửi trực tiếp dữ liệu đến đúng địa chỉ IP và port của client đó.

## 1. Yêu cầu môi trường

- Java JDK 21 hoặc mới hơn.
- Mạng cho phép UDP qua cổng `9999`.
- Server phải được khởi động trước client.

## 2. Cấu trúc project

Project được tổ chức thành đúng hai package:

```text
src/
├── module-info.java
└── udpgk/
    ├── server/
    │   ├── ExchangeRateServer.java
    │   ├── Constants.java
    │   └── RateRecord.java
    └── client/
        └── ExchangeRateClient.java
```

### `udpgk.server`

- `ExchangeRateServer`: mở UDP socket, nhận lệnh đăng ký/hủy đăng ký và gửi tỷ giá định kỳ.
- `Constants`: chứa port, lệnh giao thức, kích thước buffer và giới hạn dữ liệu.
- `RateRecord`: model của một snapshot tỷ giá, đồng thời xử lý sinh dữ liệu, serialize và parse.

### `udpgk.client`

- `ExchangeRateClient`: giao diện Swing, gửi lệnh đăng ký, nhận dữ liệu ở thread nền và cập nhật `JTable`.

## 3. Kiến trúc và workflow

```text
                   UDP Unicast
       ┌────────────────────────────────┐
       │                                │
       ▼                                │
┌──────────────┐  SUBSCRIBE       ┌──────────────┐
│ UDP Client   │ ────────────────► │ UDP Server   │
│ random port  │                  │ port 9999   │
└──────┬───────┘                  └──────┬───────┘
       │                                  │
       │  HH:mm:ss;Tokyo;Paris;Seoul      │
       ◄──────────────────────────────────┘
       │
       ▼
  Parse packet
       │
       ▼
  SwingUtilities.invokeLater
       │
       ▼
  Cập nhật JTable
```

### Quy trình khởi động server

1. Tạo `DatagramSocket` tại cổng `9999`.
2. Tạo `udp-server-receiver` để nhận lệnh từ client.
3. Tạo `udp-server-sender` để gửi dữ liệu mỗi giây.
4. Khi nhận `SUBSCRIBE`, lưu `IP + port` của client vào danh sách theo dõi.
5. Khi nhận `UNSUBSCRIBE`, xóa client khỏi danh sách.

### Quy trình đăng ký client

1. Client tạo `DatagramSocket` với port ngẫu nhiên.
2. Client gửi chuỗi `SUBSCRIBE` đến server.
3. Client khởi động `udp-client-receiver`.
4. Client chờ các packet tỷ giá từ server.

### Quy trình gửi dữ liệu

Mỗi chu kỳ một giây, server thực hiện:

1. Kiểm tra danh sách client.
2. Nếu không có client thì không sinh và không gửi dữ liệu.
3. Sinh một `RateRecord` duy nhất.
4. Chuyển record thành chuỗi và `byte[]` duy nhất.
5. Gửi cùng payload đó bằng UDP Unicast đến từng client.

### Quy trình nhận và hiển thị

1. Thread nhận mạng đọc packet bằng `DatagramSocket.receive()`.
2. Client parse chuỗi thành `RateRecord`.
3. Không cập nhật Swing trực tiếp từ thread mạng.
4. Dùng `SwingUtilities.invokeLater()` để đưa thao tác cập nhật bảng về EDT.
5. Nếu đang Pause, packet được bỏ qua ở phía client.
6. Bảng chỉ giữ lại 50 dòng mới nhất.

## 4. Giao thức dữ liệu

### Lệnh client gửi lên server

| Lệnh | Ý nghĩa |
|---|---|
| `SUBSCRIBE` | Đăng ký nhận tỷ giá |
| `UNSUBSCRIBE` | Hủy đăng ký |

### Payload tỷ giá

Định dạng:

```text
HH:mm:ss;Rate_Tokyo;Rate_Paris;Rate_Seoul
```

Ví dụ:

```text
01:01:07;135.50;105.20;1120.40
```

Payload dùng dấu `;`, không dùng JSON hoặc tên field lặp lại, và các tỷ giá được làm tròn còn hai chữ số thập phân.

## 5. Quy trình tối ưu

### 5.1. Tối ưu băng thông

Server sử dụng Unicast thay vì Broadcast:

```java
DatagramPacket packet = new DatagramPacket(data, data.length);
for (InetSocketAddress client : clients) {
    packet.setSocketAddress(client);
    socket.send(packet);
}
```

Một snapshot chỉ được serialize một lần trước vòng lặp gửi:

```java
byte[] data = snapshot.serialize().getBytes(StandardCharsets.UTF_8);
```

Các biện pháp chính:

- Chỉ gửi khi có ít nhất một client.
- Chỉ gửi một snapshot mỗi giây.
- Dùng payload text ngắn, không có metadata dư thừa.
- Làm tròn tỷ giá đến hai chữ số thập phân.
- Tái sử dụng cùng một `byte[]` cho tất cả client trong cùng chu kỳ.
- Không gửi broadcast đến các thiết bị không đăng ký.

### 5.2. Tối ưu bộ nhớ

Server dùng danh sách client thread-safe:

```java
private final Set<InetSocketAddress> clients =
        ConcurrentHashMap.newKeySet();
```

Buffer nhận UDP được cấp phát cố định:

```java
byte[] buffer = new byte[Constants.BUFFER_SIZE];
```

Client tự loại bỏ dòng cũ khi vượt quá 50 dòng:

```java
while (tableModel.getRowCount() > Constants.MAX_TABLE_ROWS) {
    tableModel.removeRow(0);
}
```

### 5.3. Tối ưu đa luồng và giao diện

- Server tách thread nhận đăng ký và thread gửi định kỳ.
- Client tách thread nhận UDP khỏi Swing EDT.
- `ConcurrentHashMap.newKeySet()` bảo vệ danh sách client khi hai thread cùng truy cập.
- `AtomicBoolean` quản lý trạng thái Pause/Resume an toàn.
- `SwingUtilities.invokeLater()` bảo đảm `JTable` chỉ được cập nhật trên EDT.

### 5.4. Phạm vi của Pause

`Pause` hiện dừng hiển thị và loại bỏ packet ở client, còn server vẫn tiếp tục gửi. Đây là đúng với yêu cầu giao diện ban đầu.

Nếu cần tối ưu băng thông thêm khi Pause, có thể bổ sung lệnh `PAUSE` và `RESUME` để server tạm dừng gửi cho riêng client đó.

## 6. Biên dịch và chạy

Mở hai cửa sổ PowerShell tại thư mục project.

### Biên dịch

```powershell
javac -encoding UTF-8 -d bin (Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
```

### Chạy server

```powershell
java -cp bin udpgk.server.ExchangeRateServer
```

Có thể chỉ định port:

```powershell
java -cp bin udpgk.server.ExchangeRateServer 9999
```

### Chạy client

```powershell
java -cp bin udpgk.client.ExchangeRateClient
```

Có thể chỉ định địa chỉ server và port:

```powershell
java -cp bin udpgk.client.ExchangeRateClient 127.0.0.1 9999
```

Nếu chạy trên hai máy khác nhau, truyền địa chỉ IP của máy chạy server cho client. Giá trị mặc định được khai báo tại `udpgk.server.Constants.SERVER_HOST`.

## 7. Xử lý đóng ứng dụng

Khi đóng client:

1. Client gửi `UNSUBSCRIBE`.
2. Server xóa endpoint khỏi danh sách client.
3. Client đóng socket UDP.

Khi server dừng, shutdown hook đóng socket và đánh thức thread gửi để giải phóng tài nguyên.

## 8. Các file liên quan

- [ExchangeRateServer.java](src/udpgk/server/ExchangeRateServer.java)
- [ExchangeRateClient.java](src/udpgk/client/ExchangeRateClient.java)
- [Constants.java](src/udpgk/server/Constants.java)
- [RateRecord.java](src/udpgk/server/RateRecord.java)
