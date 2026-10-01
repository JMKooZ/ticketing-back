# ticketing-back

대용량 트래픽 티케팅 백엔드 (Java 21 / Spring Boot 4 / Gradle)

## 로컬 실행
```powershell
.\gradlew bootRun
```
- Swagger UI : http://localhost:8080/swagger-ui/index.html
- 헬스체크   : http://localhost:8080/actuator/health
- 핑 API     : http://localhost:8080/api/ping

## 패키지 구조 (도메인 / 레이어)
```
com.example.ticketing_back
├─ config/              설정 (Swagger 등)
├─ common/              공통 (controller, dto, exception ...)
└─ <domain>/            event, order ...
    ├─ controller/
    ├─ service/
    ├─ repository/
    ├─ entity/
    └─ dto/
```
