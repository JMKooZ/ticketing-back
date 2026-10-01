# ticketing-back

대용량 트래픽 티케팅 백엔드 (Java 21 / Spring Boot 4.1 / Gradle)

> **학습 프로젝트**입니다. GitHub → AWS(ECR, EC2) CI/CD 구축을 목표로, 각 단계에서 "무엇을 / 왜" 했는지 이 문서에 기록합니다.
> API 테스트는 테스트 코드 대신 **Swagger UI**로 진행합니다.

---

## 1. 로컬 실행

```powershell
cd C:\std\ticketing-back
.\gradlew bootRun
```

| 용도 | 주소 |
|---|---|
| Swagger UI | http://localhost:8080/swagger-ui/index.html |
| 핑 API | http://localhost:8080/api/ping |
| 헬스체크 | http://localhost:8080/actuator/health |

현재 의존성에 DB(JPA, MySQL)가 없어서 **DB 없이 실행됩니다.** RDS 연결 단계에서 추가합니다.

## 2. 패키지 구조 (도메인 / 레이어)

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

---

## 3. 개발 환경 설치 (Windows): WSL2 + Docker Desktop

### 3-1. 사전 확인

- **라이선스**: Docker Desktop은 개인 사용, 교육, 소규모 업체(직원 250명 미만 & 연매출 $1,000만 미만)는 무료이고, 그보다 큰 기업의 업무용은 유료입니다. 회사 지급 PC라면 사내 정책을 먼저 확인합니다.
- **사양**: WSL 2 기능 사용, RAM 8GB 이상 권장
- **가상화**: 작업 관리자(Ctrl+Shift+Esc) → 성능 → CPU → 하단 **"가상화: 사용"** 이어야 합니다. "사용 안 함"이면 BIOS/UEFI에서 가상화(Intel VT-x / AMD SVM)를 켭니다.

### 3-2. WSL2 + Ubuntu 설치 (관리자 PowerShell)

```powershell
wsl --install            # WSL 설치 후 재부팅
wsl --status
wsl --update

wsl --list --online      # 설치 가능한 배포판 목록
wsl --install -d Ubuntu  # Ubuntu 설치 (사용자 이름/비밀번호 설정)
wsl -l -v                # Ubuntu 가 VERSION 2 로 보이면 정상
```

> `wsl -l -v` 실행 시 "설치된 배포가 없습니다"라고 나오는 것은 오류가 아니라 **Ubuntu 같은 배포판이 아직 없다**는 뜻입니다.
> Docker Desktop은 자체 WSL 배포판(`docker-desktop`)을 만들기 때문에 Ubuntu 없이도 동작하지만, 이후 Redis/Kafka 등을 WSL에서 쓸 수 있도록 함께 설치해 둡니다.

### 3-3. Docker Desktop 설치

1. https://www.docker.com/products/docker-desktop/ 에서 Windows용 설치 파일 다운로드
2. 실행 시 **"Use WSL 2 instead of Hyper-V"** 체크 확인 후 설치 (재로그인/재부팅 요구 가능)
3. 시작 메뉴에서 **Docker Desktop** 실행 (설치 후 자동 실행되지 않음) → 약관 동의, 로그인은 건너뛰어도 됨
4. 엔진 상태가 **Running(초록색)** 이면 준비 완료

명령어로 설치하는 방법:

```powershell
winget install -e --id Docker.DockerDesktop
```

### 3-4. (권장) WSL 메모리 상한 설정

WSL이 메모리를 과하게 점유하는 것을 막기 위해 `C:\Users\<사용자>\.wslconfig` 파일을 만듭니다.

```ini
[wsl2]
memory=4GB
processors=4
```

```powershell
wsl --shutdown     # 적용 (Docker Desktop도 다시 실행)
```

### 3-5. 설치 확인

```powershell
docker --version
docker run --rm hello-world     # "Hello from Docker!" 가 나오면 성공
```

### 3-6. 문제 해결

| 증상 | 조치 |
|---|---|
| `WSL 2 installation is incomplete` | 관리자 PowerShell에서 `wsl --update` 후 재시작 |
| 가상화 관련 오류 | 작업 관리자에서 가상화 상태 확인, BIOS에서 활성화 |
| 엔진이 계속 "Starting" | `wsl --shutdown` 후 Docker Desktop 재실행 |
| `error during connect` | Docker Desktop이 꺼져 있는 상태. 실행 후 재시도 |
| `--list --online` 실패 | 사내망/프록시 영향 가능. Microsoft Store에서 Ubuntu 직접 설치 |

> **대안(Docker Desktop 없이)**: WSL Ubuntu 터미널에서 Docker Engine을 직접 설치할 수 있습니다.
> `curl -fsSL https://get.docker.com | sh` → `sudo usermod -aG docker $USER` → `sudo service docker start`
> 이 경우 docker 명령은 Ubuntu 터미널에서 실행하고, 프로젝트는 `/mnt/c/std/ticketing-back`로 접근합니다.

---

## 4. Docker 기본 개념과 실습

### 4-1. 개념

| 용어 | 의미 | 비유 |
|---|---|---|
| **이미지(image)** | 앱 + 실행 환경(런타임, 라이브러리, 설정)을 포장한 **읽기 전용 파일 묶음**. 사진(그림)이 아님 | 밀키트, 설치 파일, 붕어빵 틀 |
| **컨테이너(container)** | 이미지를 실제로 **실행한 것** | 완성된 요리, 실행 중인 프로그램 |
| **태그(tag)** | 이미지 버전 꼬리표 (`nginx:1.27`, 생략 시 `latest`) | |

- 이미지 하나로 컨테이너를 여러 개 만들 수 있고, **컨테이너를 지워도 이미지는 남습니다.**
- 환경까지 같이 포장되므로 "내 PC에서는 되는데 서버에서는 안 돼요" 문제가 줄어듭니다.

CI/CD 흐름의 중심이 이미지입니다.

```
소스 코드 → (빌드) → 이미지 → (ECR에 저장) → 서버에서 내려받아 → 컨테이너로 실행
```

### 4-2. nginx 실습 (이미지 → 컨테이너)

```powershell
docker run -d --name web -p 8081:80 nginx     # 백그라운드 실행
# 브라우저: http://localhost:8081  → nginx 환영 페이지
docker ps                                      # 실행 중인 컨테이너
docker logs web                                # 접속 로그
docker exec web cat /usr/share/nginx/html/index.html   # 컨테이너 안에서 명령 실행
docker stop web                                # 중지
docker rm web                                  # 삭제
docker image ls                                # 이미지는 그대로 남아 있음
```

| 옵션 | 의미 |
|---|---|
| `-d` | 백그라운드 실행 |
| `--name web` | 컨테이너 이름 |
| `-p 8081:80` | **`내PC포트:컨테이너포트`** 연결 |
| `nginx` | 사용할 이미지 |

내 폴더를 컨테이너에 연결(`-v`)해서 내용을 바꿔 보기:

```powershell
mkdir C:\std\html
"<h1>내가 만든 첫 컨테이너 웹</h1>" | Out-File C:\std\html\index.html -Encoding utf8
docker run -d --name web -p 8081:80 -v C:\std\html:/usr/share/nginx/html nginx
docker rm -f web      # 정리
```

---

## 5. 우리 앱을 Docker 이미지로 만들기 (Step 2)

### 5-1. Dockerfile (프로젝트 루트)

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

| 줄 | 의미 |
|---|---|
| `FROM eclipse-temurin:21-jre` | 베이스 이미지. Java 21 실행 환경(JRE)이 들어 있는 남의 이미지 위에 쌓음 |
| `WORKDIR /app` | 컨테이너 안의 작업 폴더 |
| `COPY build/libs/*.jar app.jar` | 빌드된 jar를 컨테이너 안으로 복사 |
| `EXPOSE 8080` | 사용 포트 **표시**(문서화용). 실제 연결은 `-p` |
| `ENTRYPOINT [...]` | 컨테이너 시작 시 실행할 명령. `MaxRAMPercentage=75`는 컨테이너 메모리의 75%까지만 JVM 힙으로 사용 |

`COPY build/libs/*.jar`가 jar 하나만 잡는 것은 `build.gradle`에서 `jar` 태스크를 비활성화했기 때문입니다(`-plain.jar` 방지).

### 5-2. .dockerignore (프로젝트 루트)

```
.git
.gradle
.idea
build/
!build/libs/*.jar
README.md
```

### 5-3. 빌드 및 실행

```powershell
.\gradlew bootJar                              # 1) jar 생성 (build/libs/)
docker build -t ticketing-back .               # 2) 이미지 생성 (-t: 이름, 마지막 . : 현재 폴더 기준)
docker image ls                                # 3) 이미지 확인
docker run --rm -p 8080:8080 ticketing-back    # 4) 컨테이너 실행
```

> 실행 전 `bootRun`으로 띄운 앱이 있다면 8080 포트가 겹치므로 먼저 종료합니다.

### 5-4. 실험 기록

```powershell
# (1) 포트 변경: 컨테이너 안은 8080 그대로, 내 PC만 9090
docker run --rm -p 9090:8080 ticketing-back
#   → http://localhost:9090/api/ping 은 되고 localhost:8080 은 안 됨

# (2) 환경변수로 프로파일 변경 (같은 이미지, 코드 재빌드 없음)
docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod ticketing-back
#   → /api/ping 의 profile 이 "prod" 로 바뀜 (application-prod.yaml 적용)

# (3) 백그라운드 실행 / 로그 / 정리
docker run -d --name tb -p 8080:8080 ticketing-back
docker logs -f tb            # Ctrl+C 로 빠져나와도 컨테이너는 계속 실행
docker stop tb && docker rm tb
```

**(2)가 핵심**: 같은 이미지에 환경변수만 바꿔 동작을 바꿨습니다. AWS에서 DB 주소/비밀번호를 주입하는 방식이 이것입니다.

### 5-5. 이미지 크기 비교

```powershell
docker image ls
```

| 이미지 | DISK USAGE | CONTENT SIZE |
|---|---|---|
| nginx:latest | 242MB | 66.3MB |
| ticketing-back:latest | 510MB | 141MB |

- **DISK USAGE**: 디스크에 풀린 실제 크기 / **CONTENT SIZE**: 압축된 크기(ECR 전송 시 오가는 크기)
- 우리 이미지가 큰 이유는 Java 런타임이 통째로 들어 있기 때문입니다.
- 층별 크기: `docker history ticketing-back`

### 5-6. 레이어 캐시 실험

Docker는 Dockerfile의 각 줄을 **층(layer)**으로 쌓고, 입력이 같으면 이전 결과를 재사용합니다(`CACHED`).

```powershell
# 실험 1: 아무것도 안 바꾸고 다시 빌드 → 모든 단계 CACHED
docker build --progress=plain -t ticketing-back .

# 실험 2: 코드를 한 글자 바꾸고 jar만 다시 빌드
.\gradlew bootJar
docker build --progress=plain -t ticketing-back .
#   FROM, WORKDIR → CACHED / COPY(jar) 부터 다시 실행

# 실험 3: FROM 바로 아래에 `RUN echo "hello"` 추가 후 빌드
#   → 그 줄과 아래 모든 단계가 새로 실행됨 (위쪽 변경 = 아래 전체 캐시 무효화)
```

- 규칙: **위에서부터 비교하다 처음 달라진 지점부터는 전부 새로 만든다.** 그래서 자주 바뀌는 것(jar)은 아래, 거의 안 바뀌는 것(런타임)은 위에 둡니다.
- 효과가 큰 곳은 빌드보다 **전송**입니다. `docker pull` / ECR push 시 이미 가진 층은 건너뛰고 바뀐 층만 오갑니다.
- GitHub Actions 러너는 매번 새 환경이라 로컬 캐시가 자동으로 이어지지 않습니다(별도 캐시 설정 필요).

---

## 6. Docker 명령어 치트시트

| 목적 | 명령 |
|---|---|
| 이미지 목록 / 삭제 | `docker image ls` / `docker image rm <이름>` |
| 이미지 층 확인 | `docker history <이름>` |
| 실행 중 컨테이너 | `docker ps` (전체: `docker ps -a`) |
| 컨테이너 로그 | `docker logs <이름>` (실시간: `-f`) |
| 컨테이너 안에서 명령 | `docker exec <이름> <명령>` (셸: `docker exec -it <이름> sh`) |
| 중지 / 삭제 | `docker stop <이름>` / `docker rm <이름>` (강제: `docker rm -f`) |
| 종료 시 자동 삭제 | `docker run --rm ...` |
| 안 쓰는 것 정리 | `docker system prune` (주의: 사용하지 않는 리소스 삭제) |

---

## 7. Gradle 메모

`build.gradle`에서 Spring Boot BOM(버전 관리)을 `dependency-management` 플러그인 대신 Gradle 기본 기능(`platform`)으로 적용합니다.

```gradle
dependencies {
    implementation platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    compileOnly platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    annotationProcessor platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    ...
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'
}
```

- `implementation`에 건 `platform`은 `compileOnly`, `annotationProcessor`에 **적용되지 않습니다.** Gradle에서 이들은 서로 독립된 구성(configuration)이라서, Lombok처럼 그 구성으로 받는 라이브러리는 각각에 BOM을 걸어야 버전이 정해집니다.
- Spring Boot 4부터 스타터 이름이 바뀌었습니다: `spring-boot-starter-web` → `spring-boot-starter-webmvc`
- Swagger는 Boot 4 기준 springdoc **3.x** (`springdoc-openapi-starter-webmvc-ui:3.0.3`)
- Lombok은 DTO/서비스에서 `@RequiredArgsConstructor` 정도로 사용하고, **JPA 엔티티에는 `@Data`를 쓰지 않습니다.**

---

## 8. 진행 현황

- [x] Step 0. 로컬 `bootRun` + Swagger 확인
- [x] Step 1. Git / GitHub 저장소 연결
- [x] Step 2. Dockerfile 작성, 로컬 컨테이너 실행, 레이어 캐시 실험
- [ ] Step 3. AWS 수동 배포 1회 (EC2, ECR, 보안그룹, IAM 역할)
- [ ] Step 4. CI: GitHub Actions 빌드 (PR 검증)
- [ ] Step 5. CD: OIDC → ECR → SSM 배포, 헬스체크, 롤백
- [ ] Step 6. 설정/비밀 분리(프로파일, Parameter Store) + RDS 연결
- [ ] Step 7. 운영 습관 (로그, 비용 알림, 리소스 정리)
- [ ] Step 8~ 티케팅 기능 (재고/동시성 → Redis → Kafka → SSE/대기열)
