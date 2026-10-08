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

## 8. AWS 수동 배포 (Step 3)

로컬에서 만든 이미지를 ECR에 올리고, EC2에서 받아 실행해서 인터넷에서 Swagger를 여는 것이 목표입니다.
이 단계에서 만지는 서비스는 Step 5 자동화에서 그대로 쓰입니다.

| 서비스 | 역할 | 비유 |
|---|---|---|
| ECR | 이미지 저장소 | 이미지용 GitHub |
| EC2 | 컨테이너를 돌릴 서버 | 빌려 쓰는 PC |
| 보안그룹 | 서버 앞의 방화벽 | 출입 허용 목록 |
| IAM 역할 | 서버/사용자가 가진 권한 | 출입증 |

```
내 PC: docker build → docker push ──▶ [ECR] ◀── docker pull ── [EC2: docker run]
                                                                     ▲
                                                브라우저 ─ :8080 ─ [보안그룹]
```

### 8-1. 계정 안전 설정 (콘솔, 리전: 아시아 태평양(서울) ap-northeast-2)

1. 루트 계정에 MFA 설정
2. 결제 → Budgets → 월 예산 $5 알림 생성
3. IAM 사용자 생성 (루트 계정은 평소에 쓰지 않음)

| 계정 | 용도 |
|---|---|
| ticketing-dev | 웹 콘솔 작업 전부 (AdministratorAccess + MFA, 개인 학습 계정 한정) |
| ecr-push-local | 내 PC의 `aws configure` 전용 (ECR PowerUser, 콘솔 로그인 없음). **Step 5 완료 후 키 삭제** |

- 로그인 후 리전이 다른 값(예: 오하이오)으로 바뀌어 있을 수 있어서 화면을 열 때마다 확인합니다.
- 서비스는 리전별로 따로 존재합니다(IAM은 전역).
- 액세스 키는 GitHub, README, 채팅에 절대 올리지 않습니다.

### 8-2. ECR 저장소와 이미지 push

콘솔: ECR → 프라이빗 → 리포지토리 생성 (이름 `ticketing-back`)

```powershell
winget install -e --id Amazon.AWSCLI       # 설치 후 PowerShell 재시작
aws configure                              # ecr-push-local 의 키, region=ap-northeast-2, output=json
aws sts get-caller-identity                # Arn 이 ecr-push-local 인지 확인 ("나는 누구인가")

$REGION   = "ap-northeast-2"
$ACCOUNT  = aws sts get-caller-identity --query Account --output text
$REGISTRY = "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $REGISTRY
docker tag ticketing-back:latest "$REGISTRY/ticketing-back:v1"     # 복제가 아니라 이름표 추가
docker push "$REGISTRY/ticketing-back:v1"
```

### 8-3. EC2 준비 (콘솔)

**IAM 역할 `ticketing-ec2-role`** (신뢰 엔터티: EC2)

- `AmazonEC2ContainerRegistryReadOnly` : ECR에서 이미지 받기
- `AmazonSSMManagedInstanceCore` : SSH 없이 접속(Session Manager), Step 5 자동 배포에서도 사용

**EC2 인스턴스**

| 설정 | 값 |
|---|---|
| 이름 / AMI | ticketing-back / Amazon Linux 2023 |
| 인스턴스 유형 | t3.small (계정 플랜의 프리티어 표시 확인) |
| 키 페어 | 없음 (SSH 미사용, Session Manager로 접속) |
| 네트워크 | 기본 VPC, 퍼블릭 IP 자동 할당 활성화 |
| 보안그룹 `ticketing-sg` | 인바운드 TCP 8080 / 소스 **내 IP** (SSH 22번은 열지 않음) |
| 스토리지 | 20GiB gp3 |
| IAM 인스턴스 프로파일 | ticketing-ec2-role (고급 세부 정보 안쪽) |

- Swagger는 인증이 없어서 **내 IP만** 허용합니다. IP가 바뀌면 소스를 갱신합니다.
- 서버 안에는 액세스 키를 넣지 않고 **역할**을 붙입니다. 서버가 임시 자격 증명을 자동으로 받습니다.

### 8-4. 서버에서 실행 (Session Manager 접속)

```
EC2 → 인스턴스 선택 → 연결 → Session Manager 탭 → 연결
```

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker

REGION=ap-northeast-2
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.$REGION.amazonaws.com

aws ecr get-login-password --region $REGION | sudo docker login --username AWS --password-stdin $REGISTRY
sudo docker pull $REGISTRY/ticketing-back:v1

sudo docker run -d --name ticketing-back --restart unless-stopped \
  -p 8080:8080 -e SPRING_PROFILES_ACTIVE=prod \
  $REGISTRY/ticketing-back:v1

sudo docker logs -f ticketing-back                 # Ctrl+C 로 빠져나옴
curl http://localhost:8080/actuator/health         # {"status":"UP"}
```

브라우저에서는 EC2 콘솔의 **퍼블릭 IPv4 주소**로 접속합니다.

```
http://<퍼블릭IP>:8080/swagger-ui/index.html
http://<퍼블릭IP>:8080/api/ping        ← "profile":"prod"
```

### 8-5. 겪은 문제와 배운 것

| 증상 | 원인 / 교훈 |
|---|---|
| 브라우저 접속 불가 (`172.31.x.x`) | 프라이빗 IP는 VPC 내부 전용. **퍼블릭 IPv4**로 접속해야 함 |
| 서버에서 URL만 입력하면 `No such file or directory` | 셸이 주소를 프로그램 이름으로 해석. `curl <URL>` 로 실행 |
| 응답 없이 계속 로딩 | 보안그룹이 막은 경우(내 IP 변경, 사내 방화벽 등). 서버 안 `curl localhost:8080` 으로 앱 정상 여부부터 분리해서 확인 |
| `http` 가 `https` 로 바뀜 | 주소창에 `http://` 명시 |

- 서버에 키를 넣지 않았는데도 ECR 로그인이 되는 이유: 서버에 붙인 **역할**이 임시 자격 증명을 주기 때문
- 비용: 쓰지 않을 때는 인스턴스 **중지** (EBS/ECR 소액 과금은 남음). 중지 후 시작하면 퍼블릭 IP가 바뀜. 탄력적 IP를 쓰면 학습 후 반드시 해제

---

## 9. CI: GitHub Actions (Step 4)

push / PR 때마다 **"빌드와 이미지 생성이 되는지"** 자동으로 확인합니다. 이 단계에서는 AWS에 접근하지 않습니다.

`.github/workflows/ci.yml`

```yaml
name: ci

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
      - name: 소스 가져오기
        uses: actions/checkout@v4

      - name: Java 21 설치
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Gradle 설정 (의존성 캐시 포함)
        uses: gradle/actions/setup-gradle@v4

      - name: jar 빌드
        run: |
          chmod +x gradlew
          ./gradlew bootJar

      - name: Docker 이미지 빌드 (push 안 함)
        run: docker build -t ticketing-back:${{ github.sha }} .
```

| 부분 | 의미 |
|---|---|
| `on:` | 언제 실행할지 (PR 생성/갱신, main push) |
| `runs-on: ubuntu-latest` | GitHub이 빌려주는 **임시 리눅스 서버(러너)**. 끝나면 폐기되어 매번 깨끗한 상태 |
| `uses:` / `run:` | 남이 만든 재사용 액션 / 터미널 명령 직접 실행 |
| `${{ github.sha }}` | 커밋 해시. 이미지 태그로 사용 (Step 5에서 ECR 태그) |
| `concurrency` | 같은 브랜치의 이전 실행 취소 (낭비 방지) |

### 9-1. gradlew 실행 권한

Windows 커밋에서는 실행 권한 표시가 빠질 수 있어서, Git에 직접 기록합니다.

```powershell
git update-index --chmod=+x gradlew
git ls-files --stage gradlew          # 100755 로 시작하면 성공
```

### 9-2. 브랜치 보호 (Ruleset)

> status check 목록(`build`)은 워크플로가 **한 번 이상 실행된 뒤**에야 검색됩니다.
> PR 필수 규칙을 먼저 켜면 `main` 직접 push가 막혀 `ci.yml`을 올릴 수 없으므로 순서를 지킵니다.

1. Ruleset 생성: 이름 `protect-main`, target = default branch, **Enforcement: Disabled**
2. `ci.yml`을 `main`에 push → Actions 탭에서 `build`가 초록인지 확인
3. Ruleset 편집: `Require status checks to pass` → Add checks → `build` 선택, Enforcement를 **Active** 로 변경

| 설정 | 값 |
|---|---|
| Required approvals | 0 (본인 PR은 본인이 승인할 수 없어서 1 이상이면 머지가 막힘) |
| Restrict deletions / Block force pushes | 켜 둠 |

Active 이후 변경은 **브랜치 → PR → build 통과 → Merge** 순서로만 `main`에 들어갑니다.

```powershell
git switch -c feature/xxx
git add . ; git commit -m "feat: ..."
git push -u origin feature/xxx          # GitHub에서 PR 생성 → build 초록 확인 → Merge
```

### 9-3. 실험 기록

- **캐시**: 두 번째 실행에서 Gradle 단계가 더 빨라짐
- **일부러 실패**: 컴파일 오류가 있는 PR → 빨간 X, 로그에서 `jar 빌드` 단계의 에러 확인, Merge 버튼 비활성화 확인 → 수정 후 초록으로 바뀜

### 9-4. CI의 한계

이 CI는 **컴파일과 이미지 생성만** 확인합니다. 앱이 실제로 **기동되는지**(설정 오류 등)는 확인하지 못합니다.
Step 5에서 서버 헬스체크와 롤백이 이 부분을 보완합니다.

---

## 10. CD: GitHub Actions 자동 배포 (Step 5)

`main`에 머지하면 **빌드 → ECR push → EC2 배포 → 헬스체크 → 실패 시 롤백**까지 자동으로 진행됩니다.

```
PR 머지(main) → build 통과 → jar → 이미지 빌드 → ECR push (태그 = 커밋 해시)
             → SSM 으로 EC2 에서 deploy.sh 실행 → 새 컨테이너 → 헬스체크 → 실패 시 이전 버전으로 롤백
```

### 10-1. 핵심 개념: OIDC (키 없는 인증)

GitHub Actions가 "나는 `<저장소>`의 `main`에서 실행 중이다"라는 **신분증(토큰)**을 AWS에 제시하면, AWS가 역할의 **신뢰 정책**과 대조해서 임시 권한을 빌려줍니다.
AWS 액세스 키를 GitHub에 저장할 필요가 없고, 유출될 장기 키도 없습니다.

### 10-2. AWS 설정 (콘솔, ticketing-dev 로 로그인)

**① ID 제공업체**: IAM → ID 제공업체 → 공급자 추가

| 항목 | 값 |
|---|---|
| 유형 | OpenID Connect |
| 공급자 URL | `https://token.actions.githubusercontent.com` |
| 대상(Audience) | `sts.amazonaws.com` |

**② 역할 `github-actions-deploy`**: 신뢰 정책(사용자 지정)

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike":   { "token.actions.githubusercontent.com:sub": "repo:<OWNER>@<OWNER_ID>/<REPO>@<REPO_ID>:ref:refs/heads/main" }
    }
  }]
}
```

- `sub`는 **`main` 브랜치에서 실행된 워크플로만** 이 역할을 쓰게 제한합니다(PR 브랜치는 거절).
- **이 저장소의 `sub`는 `repo:<소유자>/<저장소>:...` 가 아니라 소유자/저장소의 숫자 ID가 붙은 형식**이었습니다.
  실제 값은 아래 10-9의 디버그 워크플로로 확인했습니다.

**③ 역할의 인라인 정책 `deploy-policy`** (최소 권한: 이 ECR 저장소 push + 이 서버 한 대에 명령)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "EcrAuth", "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Sid": "EcrPush", "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:InitiateLayerUpload","ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload","ecr:PutImage","ecr:BatchGetImage"],
      "Resource": "arn:aws:ecr:ap-northeast-2:<ACCOUNT_ID>:repository/ticketing-back" },
    { "Sid": "SsmSend", "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": [ "arn:aws:ec2:ap-northeast-2:<ACCOUNT_ID>:instance/<INSTANCE_ID>",
        "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript" ] },
    { "Sid": "SsmRead", "Effect": "Allow",
      "Action": ["ssm:GetCommandInvocation","ssm:ListCommandInvocations"], "Resource": "*" }
  ]
}
```

### 10-3. 서버: 배포 스크립트 `/opt/ticketing/deploy.sh`

Session Manager로 접속해서 만듭니다. Step 3에서 손으로 치던 명령을 한 번에 실행하고, **실패하면 이전 버전으로 되돌립니다.**

```bash
sudo mkdir -p /opt/ticketing
sudo tee /opt/ticketing/deploy.sh > /dev/null <<'EOF'
#!/bin/bash
set -euo pipefail
export HOME=/root                     # SSM 실행 환경에는 HOME 이 없어 docker login 이 실패할 수 있음

IMAGE="$1"
REGION=ap-northeast-2
NAME=ticketing-back

run_container() {
  docker rm -f $NAME 2>/dev/null || true
  docker run -d --name $NAME --restart unless-stopped \
    -p 8080:8080 \
    -e SPRING_PROFILES_ACTIVE=prod \
    "$1"
}

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker pull "$IMAGE"

PREV=$(docker inspect -f '{{.Config.Image}}' $NAME 2>/dev/null || true)   # 롤백 대상(현재 실행 중인 이미지)

run_container "$IMAGE"

for i in $(seq 1 30); do
  if curl -fs http://localhost:8080/actuator/health > /dev/null; then
    echo "배포 성공: $IMAGE"
    exit 0
  fi
  sleep 2
done

echo "헬스체크 실패: $IMAGE" >&2
docker logs --tail 50 $NAME >&2 || true
if [ -n "$PREV" ]; then
  echo "이전 버전으로 롤백: $PREV" >&2
  run_container "$PREV"
fi
exit 1
EOF
sudo chmod +x /opt/ticketing/deploy.sh
sudo bash -n /opt/ticketing/deploy.sh    # 문법 검사 (출력이 없으면 정상)
```

- 화면에서 `\` 가 `₩` 로 보이는 것은 한글 폰트 표시 문제입니다(`grep -n '₩' 파일` 결과가 없으면 정상).
- 서버에서 한 번 직접 실행해 보면 자동화 전에 스크립트 자체를 검증할 수 있습니다: `sudo /opt/ticketing/deploy.sh <ECR주소>/ticketing-back:v1`

### 10-4. GitHub 변수

`Settings → Secrets and variables → Actions → **Variables** 탭 → Repository variables`

| 이름 | 값 |
|---|---|
| `AWS_ROLE_ARN` | `arn:aws:iam::<ACCOUNT_ID>:role/github-actions-deploy` (IAM에서 **복사**) |
| `EC2_INSTANCE_ID` | `i-` 로 시작하는 실제 인스턴스 ID (EC2 콘솔에서 **복사**) |

비밀이 아니라서 Secrets가 아닌 **Variables**를 씁니다. 워크플로는 `vars.` 로 읽으므로 **Secrets 탭에 만들면 비어서 읽힙니다.**
값에는 ARN/ID만 넣습니다(앞뒤 공백, 따옴표, `이름 :` 같은 설명 글자 금지).

### 10-5. 워크플로 `.github/workflows/ci.yml`

`build` 잡 이름은 ruleset(필수 체크)이 검사하는 이름이라 바꾸지 않습니다.

```yaml
name: ci-cd

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

env:
  AWS_REGION: ap-northeast-2
  ECR_REPOSITORY: ticketing-back

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}   # main 배포 중에는 취소하지 않음

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: |
          chmod +x gradlew
          ./gradlew bootJar
      - run: docker build -t ticketing-back:${{ github.sha }} .

  deploy:
    needs: build
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    timeout-minutes: 15
    permissions:
      id-token: write        # OIDC 토큰 발급에 필요
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - run: |
          chmod +x gradlew
          ./gradlew bootJar

      - name: AWS 인증 (OIDC, 키 없음)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - name: ECR 로그인
        id: ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: 이미지 빌드 및 push
        run: |
          IMAGE=${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPOSITORY }}:${{ github.sha }}
          docker build -t $IMAGE .
          docker push $IMAGE
          echo "IMAGE=$IMAGE" >> $GITHUB_ENV

      - name: EC2에 배포 명령 전송 (SSM)
        run: |
          CMD_ID=$(aws ssm send-command \
            --instance-ids "${{ vars.EC2_INSTANCE_ID }}" \
            --document-name "AWS-RunShellScript" \
            --parameters "commands=[\"/opt/ticketing/deploy.sh $IMAGE\"]" \
            --query "Command.CommandId" --output text)
          echo "CMD_ID=$CMD_ID" >> $GITHUB_ENV

      - name: 배포 완료 대기 (실패하면 이 단계에서 빨간 X)
        run: |
          aws ssm wait command-executed \
            --command-id "$CMD_ID" --instance-id "${{ vars.EC2_INSTANCE_ID }}"

      - name: 서버 배포 로그 출력
        if: always()
        run: |
          aws ssm get-command-invocation \
            --command-id "$CMD_ID" --instance-id "${{ vars.EC2_INSTANCE_ID }}" \
            --query "{status:Status,out:StandardOutputContent,err:StandardErrorContent}" --output json
```

| 설계 포인트 | 이유 |
|---|---|
| `deploy`는 `push` + `main` 일 때만 | PR 단계에서는 AWS를 건드리지 않음 (PR에서는 `deploy`가 Skipped 로 표시되는 것이 정상) |
| `permissions: id-token: write` 는 `deploy` 잡에만 | OIDC 토큰이 필요한 잡에만 최소 권한 부여 |
| 이미지 태그 = `github.sha` | 서버에서 도는 코드가 정확히 어느 커밋인지 추적, 되돌리기 쉬움 |
| `cancel-in-progress` 는 PR 에서만 | main 배포 중 다음 push 가 와도 배포를 중간에 끊지 않음 |

### 10-6. 배포 확인 방법

| 어디서 | 확인 |
|---|---|
| GitHub Actions | `build`, `deploy` 모두 초록. `서버 배포 로그 출력` 의 `"status": "Success"` 와 `배포 성공: ...:<커밋해시>` |
| 브라우저 | `http://<퍼블릭IP>:8080/api/ping` 의 값이 바뀐 코드대로 나옴 |
| 서버 | `sudo docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"` 의 IMAGE 태그가 **커밋 해시**. GitHub 커밋 해시 앞부분과 비교 |
| ECR 콘솔 | `ticketing-back` 저장소에 커밋 해시 태그 이미지 생성 |
| Systems Manager | Run Command → 명령 기록에 `AWS-RunShellScript` 성공 |

### 10-7. 롤백 실험

CI는 **컴파일과 이미지 생성만** 확인하고 앱이 **기동되는지**는 확인하지 못합니다. 그래서 일부러 기동이 안 되는 설정으로 서버의 방어(헬스체크 + 롤백)를 확인했습니다.

```yaml
# application-prod.yaml 에 일부러 추가 (실험 후 되돌림)
server:
  port: abc
```

| 단계 | 결과 |
|---|---|
| PR의 `build` | **초록** (컴파일만 하므로 통과) |
| 머지 후 `deploy` | `배포 완료 대기`에서 빨간 X (서버 스크립트가 실패로 종료) |
| 서버 상태 | 헬스체크 실패를 감지하고 **이전 버전 컨테이너로 복구**. `/api/ping` 은 계속 응답 |

- `aws ssm wait command-executed` 의 `Status "Failed"` 는 서버의 `deploy.sh` 가 `exit 1` 로 끝났다는 뜻입니다(롤백 후에도 **배포는 실패로 알려야** 하므로 의도된 동작).
- 실험 후에는 **설정을 되돌리는 PR을 머지해서 `main`을 정상 상태로 복구**해야 합니다. 깨진 설정이 `main`에 남아 있으면 이후 모든 배포가 같은 이유로 실패합니다.

### 10-8. 트러블슈팅 기록

| 증상 (Actions 로그) | 원인과 교훈 |
|---|---|
| `Credentials could not be loaded ... Could not load credentials from any providers` | 변수 `AWS_ROLE_ARN`이 **비어서** 읽힘 (Secrets 탭에 만들었거나 이름 오타) |
| `Source Account ID is needed if the Role Name is provided and not the Role Arn` | 변수 값이 `arn:aws:iam::...` 형식이 아님. 안내 문구째로 붙여넣었거나 따옴표/공백이 섞임 → IAM에서 ARN 복사 |
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 이 문구는 `sub` 불일치, `aud` 불일치, 존재하지 않는 역할 등 **원인이 여러 개**. 아래 10-9 방법으로 실제 값을 대조해서 해결. 이 저장소는 `sub` 에 **숫자 ID** 가 포함되어 있었음 |
| `Value '[]' at 'instanceIds' failed to satisfy constraint` | 변수 `EC2_INSTANCE_ID`가 비어서 읽힘 |
| `Invalid length for parameter CommandId, value: 0` (로그 출력 단계) | 앞 단계 실패로 `CMD_ID` 가 비었는데 `if: always()` 단계가 실행된 **부수 효과** (진짜 원인은 앞 단계) |
| 서버 터미널에서 `iam:GetRole` 이 AccessDenied | 서버 역할(`ticketing-ec2-role`)에는 IAM 조회 권한이 없음 (**의도된 최소 권한**). 콘솔 CloudShell(ticketing-dev)에서 실행해야 함 |

배운 점: 같은 에러 문구라도 원인이 여러 개일 수 있으므로, **설정 화면을 눈으로 비교하기보다 실제 값을 출력해서 대조**하는 것이 가장 빠릅니다.

### 10-9. 디버깅 도구

**CloudShell** (콘솔 우측 상단 `>_`, 로그인한 IAM 사용자 권한으로 실행)

```bash
aws sts get-caller-identity                                  # 내가 누구인지
aws iam get-role --role-name github-actions-deploy --query "Role.[Arn,AssumeRolePolicyDocument]" --output json
aws iam list-open-id-connect-providers
aws iam get-open-id-connect-provider --open-id-connect-provider-arn arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com
```

**OIDC 토큰 클레임 출력 워크플로** (별도 브랜치에서 실행하고 `main`에는 합치지 않음. 토큰 자체는 출력하지 않음)

```yaml
name: oidc-debug
on:
  push:
    branches: [debug/oidc]
permissions:
  id-token: write
  contents: read
jobs:
  show-claims:
    runs-on: ubuntu-24.04
    steps:
      - run: |
          python3 - <<'PY'
          import os, json, base64, urllib.request
          url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"] + "&audience=sts.amazonaws.com"
          req = urllib.request.Request(url, headers={"Authorization": "bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
          jwt = json.load(urllib.request.urlopen(req))["value"]
          payload = jwt.split(".")[1]
          payload += "=" * (-len(payload) % 4)
          claims = json.loads(base64.urlsafe_b64decode(payload))
          for k in ["iss", "aud", "sub", "repository", "repository_owner", "ref"]:
              print(k, "=", claims.get(k))
          PY
```

### 10-10. 마무리 정리

- [ ] 롤백 실험 후 깨진 설정을 되돌리는 PR 머지 (`main`에 남은 `server.port: abc` 제거)
- [ ] 수동 단계용 IAM 사용자 `ecr-push-local`의 액세스 키 비활성화 후 삭제, PC의 `~/.aws` 자격 증명 삭제
- [ ] 디버그 브랜치(`debug/oidc`) 삭제 (`git push origin --delete debug/oidc`)
- [ ] 워크플로 경고 정리: `ubuntu-latest` → `ubuntu-24.04` 고정, `actions/setup-java@v4` → `v5`, `서버 배포 로그 출력` 의 조건을 `if: always() && env.CMD_ID != ''` 로 (한 번에 하나씩 PR)

---

## 11. RDS 연결과 비밀 관리 (Step 6)

앱이 DB를 쓰게 만들고, **DB 비밀번호를 코드/이미지/GitHub에 두지 않고** Parameter Store에서 가져옵니다.

```
브라우저 ─ :8080 ─▶ [EC2 + 앱 컨테이너]  (보안그룹 ticketing-sg)
                         │ 3306 (ticketing-sg 에서 오는 요청만 허용)
                         ▼
                    [RDS MySQL ticketing-db]  (보안그룹 ticketing-db-sg, 퍼블릭 액세스 없음)

Parameter Store ──(서버 역할이 읽기)──▶ 접속 정보(URL / 사용자 / 비밀번호)
```

### 11-1. RDS 생성 설정 (콘솔, 서울 리전)

| 항목 | 값 |
|---|---|
| 엔진 / 버전 | MySQL 8.4.x |
| 가용성 | 단일 DB 인스턴스 (다중 AZ 아님) |
| 식별자 / 마스터 사용자 | `ticketing-db` / `admin` (암호는 자체 관리, **문서/채팅에 기록하지 않음**) |
| 인스턴스 / 스토리지 | `db.t4g.micro` / gp3 20GiB, 스토리지 자동 조정 해제 |
| 연결 | 기본 VPC, **퍼블릭 액세스 아니요**, 보안그룹 **새로 생성 `ticketing-db-sg`** (`default` 공용 그룹은 쓰지 않음) |
| 추가 구성 | **초기 데이터베이스 이름 `ticketing`**, 백업 보존 1일, 삭제 방지 해제(학습용), 향상된 모니터링 해제 |

- 초기 DB 이름을 비워 두면 `Unknown database 'ticketing'` 으로 앱이 실패합니다.
- Database Insights는 기본(표준)만 사용하고 Advanced는 선택하지 않습니다(추가 요금).

### 11-2. 보안그룹 참조 (핵심 개념)

`ticketing-db-sg` 인바운드: **MySQL/Aurora 3306, 소스 = 보안그룹 `ticketing-sg`**

- IP 대역이 아니라 "`ticketing-sg` 가 붙은 리소스에서 오는 요청만" 허용합니다. 서버 IP가 바뀌어도 규칙을 고칠 필요가 없습니다.
- 퍼블릭 액세스를 막았기 때문에 **내 PC에서 RDS로 직접 접속할 수 없는 것이 정상**입니다.
- 서버에서 연결 확인: `timeout 3 bash -c "</dev/tcp/<RDS_ENDPOINT>/3306" && echo "3306 열림" || echo "3306 막힘"`

### 11-3. Parameter Store

리전별로 따로 저장되고, **이름은 수정할 수 없습니다**(삭제 후 재생성). 비밀번호는 **SecureString** 으로 저장합니다.

| 이름 | 유형 | 용도 |
|---|---|---|
| `/ticketing/prod/db-url` | String | `jdbc:mysql://<RDS_ENDPOINT>:3306/ticketing?serverTimezone=Asia/Seoul` |
| `/ticketing/prod/db-username` | String | `admin` |
| `/ticketing/prod/db-password` | SecureString | RDS 마스터 암호 |
| `/config/ticketing-back_prod/spring.datasource.url` | String | 같은 값 (아래 참고) |
| `/config/ticketing-back_prod/spring.datasource.username` | String | 같은 값 |
| `/config/ticketing-back_prod/spring.datasource.password` | SecureString | 같은 값 |

> 이 이름 규칙(`/config/<앱이름>_<프로파일>/<속성이름>`)으로 파라미터를 추가한 뒤 RDS 연동 배포가 성공했습니다.
> **TODO**: 소스에서 이 값을 읽도록 바꾼 내용(의존성, `spring.config.import` 등)을 여기에 기록합니다.
> `deploy.sh` 가 아직 `/ticketing/prod/*` 를 읽는 동안에는 그 파라미터를 지우지 않습니다(지우면 배포 스크립트가 시작 단계에서 실패).

CLI로 만들기 (CloudShell, 오타 방지). 비밀번호는 명령줄에 쓰지 않고 입력창으로 받습니다. **세 줄을 한 줄씩 따로 실행**합니다.

```bash
aws ssm put-parameter --region ap-northeast-2 --name /ticketing/prod/db-username --type String --value "admin"

read -s -p "RDS 마스터 암호: " DBPW; echo          # 입력 중 글자가 보이지 않는 것이 정상
aws ssm put-parameter --region ap-northeast-2 --name /ticketing/prod/db-password --type SecureString --value "$DBPW" --overwrite
unset DBPW
```

서버 역할(`ticketing-ec2-role`)의 읽기 정책 (경로 한정, 최소 권한):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"],
    "Resource": [
      "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/ticketing/prod/*",
      "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/config/ticketing-back_prod/*"
    ]
  }]
}
```

### 11-4. 앱 연결 (로컬 개발)

```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
runtimeOnly 'com.mysql:mysql-connector-j'
```

의존성이 들어가면 **로컬에서도 DB가 없으면 앱이 시작되지 않습니다.** 로컬용 MySQL은 Docker로 띄웁니다(호스트 포트 3307: PC에 MySQL이 있어도 충돌하지 않음). **백틱 없이 한 줄**로 실행합니다.

```powershell
docker run -d --name ticketing-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ticketing mysql:8.4
docker logs ticketing-mysql --tail 5        # "ready for connections" 확인 (첫 실행은 10~20초)
.\gradlew bootRun
```

- 로컬 확인: Swagger `GET /api/db-ping` → `{"db":"ticketing", ...}`, `/actuator/health` → `UP`
- `application.yaml`의 `spring:` 키는 한 번만 써야 합니다(YAML은 중복 키가 에러).
- 학습 실험: `docker stop ticketing-mysql` → `/actuator/health` 가 `DOWN`, `/api/db-ping` 이 500 → `docker start ticketing-mysql` 로 복구. DB가 안 붙는 버전은 헬스체크가 DOWN이라 배포가 롤백되는 이유입니다.

### 11-5. deploy.sh 최종본 (서버 `/opt/ticketing/deploy.sh`)

Step 5 버전에서 달라진 점: ① 파라미터를 읽어 컨테이너에 주입, ② **마지막 정상 이미지**를 기록해서 롤백 대상으로 사용, ③ 실패 시 핵심 에러를 먼저 출력.

```bash
sudo tee /opt/ticketing/deploy.sh > /dev/null <<'EOF'
#!/bin/bash
set -euo pipefail
export HOME=/root

IMAGE="$1"
REGION=ap-northeast-2
NAME=ticketing-back
LAST_GOOD_FILE=/opt/ticketing/last_good_image    # 마지막으로 헬스체크를 통과한 이미지

get_param() {
  aws ssm get-parameter --name "/ticketing/prod/$1" --with-decryption \
    --region $REGION --query Parameter.Value --output text
}

# 컨테이너를 건드리기 전에 먼저 읽음: 실패하면 기존 서비스는 그대로 유지됨
export DB_URL="$(get_param db-url)"
export DB_USERNAME="$(get_param db-username)"
export DB_PASSWORD="$(get_param db-password)"

run_container() {
  docker rm -f $NAME 2>/dev/null || true
  docker run -d --name $NAME --restart unless-stopped \
    -p 8080:8080 \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
    "$1"
}

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker pull "$IMAGE"

PREV=$(docker inspect -f '{{.Config.Image}}' $NAME 2>/dev/null || true)

run_container "$IMAGE"

for i in $(seq 1 30); do
  if curl -fs http://localhost:8080/actuator/health > /dev/null; then
    echo "$IMAGE" > "$LAST_GOOD_FILE"
    echo "배포 성공: $IMAGE"
    exit 0
  fi
  sleep 2
done

echo "헬스체크 실패: $IMAGE" >&2
echo "---- 핵심 에러 ----" >&2
docker logs $NAME 2>&1 | grep -E "Caused by|ERROR|Access denied|Communications|Exception" | tail -n 15 >&2 || true
echo "---- 마지막 로그 30줄 ----" >&2
docker logs --tail 30 $NAME >&2 || true

ROLLBACK_TO=$(cat "$LAST_GOOD_FILE" 2>/dev/null || true)
if [ -z "$ROLLBACK_TO" ]; then ROLLBACK_TO="$PREV"; fi
if [ -n "$ROLLBACK_TO" ] && [ "$ROLLBACK_TO" != "$IMAGE" ]; then
  echo "마지막 정상 버전으로 롤백: $ROLLBACK_TO" >&2
  run_container "$ROLLBACK_TO"
fi
exit 1
EOF
sudo chmod 700 /opt/ticketing/deploy.sh
sudo bash -n /opt/ticketing/deploy.sh      # 출력이 없으면 문법 정상
```

| 설계 포인트 | 이유 |
|---|---|
| `-e DB_URL` 처럼 **값 없이 이름만** 전달 | 값이 `docker run` 명령줄(`ps`, 로그)에 노출되지 않음 |
| 파라미터 읽기를 컨테이너 교체 **전**에 수행 | 읽기가 실패하면(`set -e`) 기존 컨테이너를 지우기 전에 중단 |
| `last_good_image` 기록 | 롤백 대상이 "지금 실행 중인 것"이면 연속 실패 시 **깨진 이미지를 다시 띄우는** 문제가 생김 |
| `set -x` 사용 금지 | 켜면 비밀번호가 로그에 찍힘 |

> 발견한 문제: Step 5의 롤백은 "배포 직전에 실행 중이던 이미지"로 되돌렸습니다. 실패한 배포를 다시 실행하면 그 깨진 이미지가 "직전 이미지"가 되어, 롤백이 같은 이미지를 다시 띄웠습니다.

### 11-6. 트러블슈팅 기록

| 증상 | 원인과 교훈 |
|---|---|
| `ParameterNotFound` | 해당 **리전에 그 이름의 파라미터가 없음**. 권한 문제는 `AccessDenied`로 나옴. 목록은 CloudShell에서 `aws ssm describe-parameters --region ap-northeast-2 --query "Parameters[].Name" --output text` |
| 서버 터미널에서 `iam:GetRole` 등이 AccessDenied | 서버 역할에는 IAM/목록 조회 권한이 없음 (**의도된 최소 권한**). 조회/생성은 CloudShell(ticketing-dev)에서 |
| `Unable to determine Dialect without JDBC metadata` | **DB 접속 실패의 이차 에러**. 진짜 원인(`Access denied`, 설정값 불일치 등)은 그 위 로그에 있음. 로그를 꼬리 50줄만 보면 잘리므로 `grep "Caused by"` |
| 배포는 초록인데 설정값이 안 읽힘 | 환경변수/파라미터 **이름 불일치**. 앱이 읽는 이름과 저장된 이름이 같은지 확인 |
| `docker` 명령이 `dockerDesktopLinuxEngine` 연결 실패 | **Docker Desktop이 꺼져 있음**. 실행 후 `docker version` 에서 Server 블록 확인 |
| 여러 줄 명령이 안 먹음 | PowerShell 줄바꿈 백틱은 줄 **맨 끝**에만. 붙여넣기에서 깨지면 한 줄로 실행. 비밀번호 `read -s` 는 **한 줄씩 따로** 실행(한 번에 붙이면 다음 줄이 비밀번호로 읽힘) |
| 서버는 정상인데 집에서만 접속 불가 | 보안그룹이 **다른 위치의 공인 IP**를 허용하지 않음 → **12장** |

### 11-7. 비밀 취급 수칙

- 비밀번호/토큰/키는 **채팅, README, 스크립트, 코드, 스크린샷에 쓰지 않습니다.** 노출되었다면 유출된 것으로 보고 교체합니다.
- `deploy.sh` 에 값을 직접 쓰지 않습니다(**하드코딩 금지**). 비밀번호를 바꿀 때 스크립트를 고치게 되고, 서버 디스크에 평문이 남습니다.
- 명령줄에 비밀번호를 직접 쓰면 셸 기록(`history`)에 남습니다. 입력창(`read -s`)으로 받습니다.
- Parameter Store는 값이 아니라 **이름만** 조회해서 확인합니다 (`--query Parameter.Name`).

### 11-8. Session Manager와 CloudShell 구분

| | Session Manager | CloudShell |
|---|---|---|
| 모양 | 새 탭의 검은 화면 (`세션 ID`, `Instance ID` 표시) | 콘솔 화면 **하단 패널** (`>_` 아이콘) |
| 실행 권한 | 서버 역할 `ticketing-ec2-role` | 로그인한 사용자 `ticketing-dev` |
| 용도 | 서버 안의 작업 (docker, deploy.sh) | AWS 리소스 조회/생성/설정 |

헷갈릴 때는 `aws sts get-caller-identity` 로 현재 실행 주체(`Arn`)를 확인합니다.

Session Manager는 **새 세션을 열 때마다 변수가 초기화**되므로, 시작할 때 아래를 붙여넣습니다.

```bash
bash
export REGION=ap-northeast-2
export ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
export REGISTRY=$ACCOUNT.dkr.ecr.$REGION.amazonaws.com
export ENDPOINT=<RDS_ENDPOINT>
alias dps='sudo docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"'
```

매번 붙여넣기 번거로우면 `Systems Manager → Session Manager → 기본 설정 → Linux shell profile` 에 `export` 줄들과 마지막 줄 `exec /bin/bash` 를 넣습니다(모든 세션에 적용되므로 **비밀 값은 넣지 않음**).

---

## 12. 접속 허용 IP 추가: 집/회사에서 접속할 때

### 12-1. 왜 필요한가

`ticketing-sg` 의 8080 규칙은 **"내 IP"** 로 만들었습니다. 이 옵션은 규칙을 만들 때 쓰던 네트워크의 **공인 IP 한 개(`x.x.x.x/32`)** 만 허용하고, 다른 곳에서 오는 요청은 **응답 없이 버립니다**(에러 없이 로딩만 계속되다 시간 초과).

집, 회사, 핫스팟은 공인 IP가 서로 다르기 때문에, 서버가 정상이어도 다른 위치에서는 접속되지 않습니다. 집 인터넷은 공유기 재시작 등으로 IP가 바뀌기도 합니다(유동 IP).

### 12-2. 콘솔로 추가하는 방법 (가장 쉬움)

**접속하려는 PC(집)에서** AWS 콘솔에 `ticketing-dev` 로 로그인한 상태에서 진행합니다. 그래야 "내 IP"가 집 IP로 채워집니다.

```
1. 우측 상단 리전이 "아시아 태평양(서울) ap-northeast-2" 인지 확인
2. EC2 → 왼쪽 메뉴 "보안 그룹" → ticketing-sg 선택
3. 하단 [인바운드 규칙] 탭 → "인바운드 규칙 편집"
4. "규칙 추가"
     - 유형   : 사용자 지정 TCP
     - 포트 범위 : 8080
     - 소스   : "내 IP"  (현재 PC의 공인 IP가 자동 입력됨)
     - 설명   : home   (위치를 구분할 이름. 나중에 정리하기 쉬움)
5. "규칙 저장"
```

기존 규칙(원래 위치)은 **지우지 않고 그대로 둔 채 규칙을 추가**하면 두 위치 모두 접속됩니다. 규칙은 몇 초 안에 반영됩니다.

### 12-3. CLI로 추가하는 방법 (CloudShell)

**주의**: IP 확인은 **접속하려는 PC**에서 해야 합니다. CloudShell에서 확인하면 CloudShell의 IP가 나옵니다.

```powershell
# 접속하려는 PC (PowerShell)
curl.exe -s https://checkip.amazonaws.com
```

```bash
# CloudShell (ticketing-dev): 위에서 나온 IP를 넣음
SG_ID=$(aws ec2 describe-security-groups --region ap-northeast-2 --filters Name=group-name,Values=ticketing-sg --query "SecurityGroups[].GroupId" --output text)

aws ec2 authorize-security-group-ingress --region ap-northeast-2 --group-id $SG_ID \
  --ip-permissions 'IpProtocol=tcp,FromPort=8080,ToPort=8080,IpRanges=[{CidrIp=<위에서 확인한 IP>/32,Description=home}]'
```

현재 허용된 목록 확인 / 이전 위치 규칙 삭제:

```bash
aws ec2 describe-security-groups --region ap-northeast-2 --group-ids $SG_ID \
  --query "SecurityGroups[].IpPermissions[].IpRanges[]" --output json

aws ec2 revoke-security-group-ingress --region ap-northeast-2 --group-id $SG_ID \
  --protocol tcp --port 8080 --cidr <삭제할 IP>/32
```

### 12-4. 접속 안 될 때 점검 순서

| 순서 | 확인 | 방법 |
|---|---|---|
| 1 | 서버 상태와 **현재 퍼블릭 IP** | `aws ec2 describe-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> --query "Reservations[].Instances[].[State.Name,PublicIpAddress]" --output text` |
| 2 | 내 PC에서 포트 도달 | `Test-NetConnection <퍼블릭IP> -Port 8080` (`TcpTestSucceeded : True` 여야 함) |
| 3 | 내 공인 IP가 허용 목록에 있는지 | `curl.exe -s https://checkip.amazonaws.com` 과 보안그룹 규칙 비교 |
| 4 | 서버 안에서는 정상인지 | Session Manager에서 `curl http://localhost:8080/actuator/health` → `UP` |

- 4번은 정상인데 2번이 `False` 이면 거의 항상 **보안그룹(IP)** 문제입니다.
- Session Manager는 IP 허용과 무관하게 콘솔 로그인만 되면 접속되므로, 서버 상태를 확인하는 우회로가 됩니다.

### 12-5. 주의사항

- **`0.0.0.0/0`(전체 공개)으로 여는 것은 하지 않습니다.** Swagger에는 인증이 없어서 누구나 API를 실행할 수 있습니다.
- EC2를 **중지했다가 다시 시작하면 퍼블릭 IP가 바뀝니다.** 접속 주소도 새로 확인합니다(위 12-4의 1번). 주소를 고정하려면 탄력적 IP(Elastic IP)를 연결할 수 있지만, 퍼블릭 IPv4 요금이 있고 **안 쓰는 탄력적 IP를 방치하면 과금**되므로 학습이 끝나면 반드시 해제합니다.
- 더 이상 쓰지 않는 위치의 규칙은 삭제해서 허용 범위를 줄입니다.
- RDS는 퍼블릭 액세스가 없으므로 PC에서 직접 접속할 수 없는 것이 정상입니다.

---

## 13. 운영 습관 (Step 7)

기능이 아니라 **비용과 사고를 줄이는 루틴**입니다. 학습용 계정에서 가장 흔한 문제는 방치된 리소스에 요금이 쌓이는 것입니다.

### 13-1. 켜고 끄는 루틴 (CloudShell)

쓰지 않을 때는 중지합니다(EC2는 인스턴스 시간 요금, RDS는 인스턴스 요금이 멈추고 디스크 요금은 남음).

```bash
export AWS_PAGER=""      # 출력이 길어질 때 열리는 페이저(less, "(END)") 끄기. 페이저가 열리면 q 로 종료

# 끌 때
aws ec2 stop-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> --query "StoppingInstances[].CurrentState.Name" --output text
aws rds stop-db-instance --region ap-northeast-2 --db-instance-identifier ticketing-db --query "DBInstance.DBInstanceStatus" --output text

# 켤 때: RDS 먼저, 그다음 EC2
aws rds start-db-instance --region ap-northeast-2 --db-instance-identifier ticketing-db --query "DBInstance.DBInstanceStatus" --output text
aws rds wait db-instance-available --region ap-northeast-2 --db-instance-identifier ticketing-db
aws ec2 start-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> --query "StartingInstances[].CurrentState.Name" --output text
aws ec2 wait instance-running --region ap-northeast-2 --instance-ids <INSTANCE_ID>

# 새 퍼블릭 IP 확인
aws ec2 describe-instances --region ap-northeast-2 --instance-ids <INSTANCE_ID> --query "Reservations[].Instances[].[State.Name,PublicIpAddress]" --output text
```

직접 확인한 규칙들:

| 규칙 | 설명 |
|---|---|
| `stopping` 중에는 **시작 불가** | `stopped` 가 된 뒤에만 `start-db-instance` 가능 (`InvalidDBInstanceState`) |
| 중지 상태에서는 **수정 불가** | `Cannot modify a stopped DB Instance`. 켠 뒤에 수정 |
| 중지 후 **7일이 지나면 RDS가 자동으로 다시 시작**됨 | 오래 안 쓸 때는 다시 중지 |
| EC2를 켜면 **퍼블릭 IP가 바뀜** | 접속 주소를 새로 확인. 보안그룹의 "내 IP" 규칙은 그대로 유효 |
| 컨테이너는 `--restart unless-stopped` 덕분에 **EC2가 켜지면 자동으로 올라옴** | 배포 없이 1~2분 안에 `UP` |
| 앱은 RDS가 준비되기 전에는 DB 연결 실패로 재시작을 반복 | RDS를 먼저 `available` 로 만들면 해당 없음 |

`wait` 명령은 몇 분 동안 아무 출력 없이 기다리는 것이 정상입니다. 출력이 길 때는 `--query` 로 필요한 값만 뽑는 습관을 들입니다.

### 13-2. 설정 점검 사례: 출력 전체 읽기

`stop-db-instance` 의 긴 출력은 RDS의 **실제 설정 전체**입니다. 여기서 안내와 다르게 들어간 두 항목을 발견했습니다.

| 출력 | 의미 | 조치 |
|---|---|---|
| `MonitoringInterval: 60` | 향상된 모니터링이 켜져 있음 (CloudWatch 로그에 OS 지표 저장 → 비용) | 해제 |
| `MaxAllocatedStorage: 1000` | 스토리지 자동 조정이 켜져 있고 1,000GiB까지 커질 수 있음 (비용 사고 위험) | 해제 |

중지 상태에서는 수정이 안 되므로 **RDS를 켠 뒤** 수정했습니다.

```bash
aws rds modify-db-instance --region ap-northeast-2 --db-instance-identifier ticketing-db \
  --monitoring-interval 0 --max-allocated-storage 20 --apply-immediately --query "DBInstance.DBInstanceStatus" --output text
aws rds wait db-instance-available --region ap-northeast-2 --db-instance-identifier ticketing-db

# 확인: available  0  None  20  이면 성공
aws rds describe-db-instances --region ap-northeast-2 --db-instance-identifier ticketing-db \
  --query "DBInstances[].[DBInstanceStatus,MonitoringInterval,MaxAllocatedStorage,AllocatedStorage]" --output text
```

- 두 설정은 재시작 없이 적용되는 종류라 서비스가 끊기지 않습니다.
- 보안그룹이 DB 전용(`ticketing-db-sg`)인지도 이름으로 확인합니다: `aws ec2 describe-security-groups --region ap-northeast-2 --group-ids <SG_ID> --query "SecurityGroups[].GroupName" --output text`
- 향상된 모니터링을 켜면서 생긴 IAM 역할 `rds-monitoring-role` 과 로그 그룹 `RDSOSMetrics` 는 남아 있으므로 학습 종료 시 정리합니다(13-6).
- 교훈: 콘솔이 기본값으로 켜 두는 옵션이 있으므로, 생성 후 **실제 설정 값을 한 번 출력해서 확인**합니다.

### 13-3. ECR 이미지 정리 (수명 주기 정책)

배포할 때마다 이미지가 하나씩 쌓이므로 오래된 이미지를 자동으로 지웁니다.

```bash
cat > ecr-policy.json <<'EOF'
{
  "rules": [{
    "rulePriority": 1,
    "description": "최근 10개 이미지만 보관",
    "selection": { "tagStatus": "any", "countType": "imageCountMoreThan", "countNumber": 10 },
    "action": { "type": "expire" }
  }]
}
EOF

aws ecr put-lifecycle-policy --region ap-northeast-2 --repository-name ticketing-back --lifecycle-policy-text file://ecr-policy.json
aws ecr get-lifecycle-policy --region ap-northeast-2 --repository-name ticketing-back --query lifecyclePolicyText --output text   # 확인
```

- 10개면 `last_good_image`(롤백 대상)가 지워질 일은 거의 없습니다. 같은 이미지가 오래 정상으로 돌고 그동안 실패 배포만 10번 넘게 쌓이면 이론상 지워질 수 있으니, 그런 일이 생기면 개수를 늘립니다.

### 13-4. 비용 확인

콘솔: `Billing and Cost Management`

| 메뉴 | 보는 것 |
|---|---|
| 크레딧 | 남은 크레딧과 만료일 |
| 청구서(Bills) → 서비스별 요금 | **무엇이 비용을 만드는지**. 크레딧 적용 전 사용 금액과 적용 후 청구 금액을 구분해서 봄 |
| 프리 티어 | 서비스별 한도 대비 사용량 |
| 예산(Budgets) | 월 예산과 알림 (월 $10, 이메일 알림으로 설정) |
| 비용 탐색기(Cost Explorer) | 서비스별/일별 그래프 (처음 켜면 데이터가 채워지는 데 최대 하루). CLI(`aws ce`)는 요청당 소액 과금이므로 학습 중에는 콘솔 사용 |

이 프로젝트의 비용 요소:

| 서비스 | 요금이 생기는 이유 | 줄이는 방법 |
|---|---|---|
| EC2 | 인스턴스 시간, 디스크(EBS), 퍼블릭 IPv4 | 쓰지 않을 때 중지 |
| RDS | 인스턴스 시간, 스토리지, 백업 | 쓰지 않을 때 중지, 백업 보존 1일 |
| ECR | 이미지 저장 용량 | 수명 주기 정책 |
| CloudWatch | 로그 수집/보관 | 보관 기간 7일, 향상된 모니터링 해제 |

- 크레딧이 적용되면 청구 금액이 0원으로 보여서 **예산 알림이 울리지 않을 수 있습니다.** 예산 설정에 크레딧을 제외하는 옵션이 있으면 사용량 기준으로 바꾸고, "예측 비용 초과" 알림을 하나 더 추가합니다(옵션 이름과 기본값은 화면 버전에 따라 다름).
- 습관: 사용 후 RDS/EC2 중지, 주 1회 서비스별 청구서 확인, 연결 안 된 탄력적 IP/스냅샷/볼륨 정리, 리전은 서울 하나로 고정(Tag Editor 에서 "모든 리전"으로 방치된 리소스 점검).

### 13-5. 로그를 CloudWatch Logs로 보내기

컨테이너 로그가 컨테이너 안에만 있으면 배포 때 `docker rm -f` 와 함께 사라집니다. 서버 밖(CloudWatch)에 두면 컨테이너나 서버가 없어져도 남습니다.

```
앱 컨테이너 ──(docker awslogs 드라이버, 서버 역할 권한)──▶ CloudWatch Logs: /ticketing/back
```

**A. 로그 그룹 (보관 기간을 정해야 무한히 쌓이지 않음)**

```bash
aws logs create-log-group --region ap-northeast-2 --log-group-name /ticketing/back
aws logs put-retention-policy --region ap-northeast-2 --log-group-name /ticketing/back --retention-in-days 7
```

**B. 서버 역할에는 이 로그 그룹에 쓰는 권한만** (그룹 생성/읽기/삭제 권한은 주지 않음)

```bash
aws iam put-role-policy --role-name ticketing-ec2-role --policy-name write-ticketing-logs --policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["logs:CreateLogStream", "logs:PutLogEvents"],
    "Resource": "arn:aws:logs:ap-northeast-2:<ACCOUNT_ID>:log-group:/ticketing/back:*"
  }]
}'
```

**C. 운영 컨테이너를 건드리기 전에 작은 컨테이너로 권한 시험 (Session Manager)**

```bash
sudo docker run --rm --log-driver awslogs \
  --log-opt awslogs-region=ap-northeast-2 --log-opt awslogs-group=/ticketing/back \
  --log-opt awslogs-stream=test-$(date +%s) \
  alpine echo "hello cloudwatch"
```

**D. deploy.sh 최종본** (`/opt/ticketing/deploy.sh`). 로그 드라이버 옵션이 추가되었고, `docker run` 자체가 실패해도 롤백 경로를 타도록 고쳤습니다.

```bash
sudo tee /opt/ticketing/deploy.sh > /dev/null <<'EOF'
#!/bin/bash
set -euo pipefail
export HOME=/root

IMAGE="$1"
REGION=ap-northeast-2
NAME=ticketing-back
LAST_GOOD_FILE=/opt/ticketing/last_good_image    # 마지막으로 헬스체크를 통과한 이미지
LOG_GROUP=/ticketing/back

get_param() {
  aws ssm get-parameter --name "/ticketing/prod/$1" --with-decryption \
    --region $REGION --query Parameter.Value --output text
}

# 컨테이너를 건드리기 전에 먼저 읽음: 실패하면 기존 서비스는 그대로 유지됨
export DB_URL="$(get_param db-url)"
export DB_USERNAME="$(get_param db-username)"
export DB_PASSWORD="$(get_param db-password)"

run_container() {
  docker rm -f $NAME 2>/dev/null || true
  docker run -d --name $NAME --restart unless-stopped \
    -p 8080:8080 \
    -e SPRING_PROFILES_ACTIVE=prod \
    -e DB_URL -e DB_USERNAME -e DB_PASSWORD \
    --log-driver awslogs \
    --log-opt awslogs-region=$REGION \
    --log-opt awslogs-group=$LOG_GROUP \
    "$1"
}

aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin "${IMAGE%%/*}"
docker pull "$IMAGE"

PREV=$(docker inspect -f '{{.Config.Image}}' $NAME 2>/dev/null || true)

healthy=0
if run_container "$IMAGE"; then
  for i in $(seq 1 30); do
    if curl -fs http://localhost:8080/actuator/health > /dev/null; then
      healthy=1
      break
    fi
    sleep 2
  done
else
  echo "컨테이너 시작 실패: $IMAGE" >&2
fi

if [ "$healthy" -eq 1 ]; then
  echo "$IMAGE" > "$LAST_GOOD_FILE"
  echo "배포 성공: $IMAGE"
  exit 0
fi

echo "헬스체크 실패: $IMAGE" >&2
echo "---- 핵심 에러 ----" >&2
docker logs $NAME 2>&1 | grep -E "Caused by|ERROR|Access denied|Communications|Exception" | tail -n 15 >&2 || true
echo "---- 마지막 로그 30줄 ----" >&2
docker logs --tail 30 $NAME >&2 || true

ROLLBACK_TO=$(cat "$LAST_GOOD_FILE" 2>/dev/null || true)
if [ -z "$ROLLBACK_TO" ]; then ROLLBACK_TO="$PREV"; fi
if [ -n "$ROLLBACK_TO" ] && [ "$ROLLBACK_TO" != "$IMAGE" ]; then
  echo "마지막 정상 버전으로 롤백: $ROLLBACK_TO" >&2
  run_container "$ROLLBACK_TO"
fi
exit 1
EOF
sudo chmod 700 /opt/ticketing/deploy.sh
sudo bash -n /opt/ticketing/deploy.sh      # 출력이 없으면 문법 정상
```

| 변경 | 이유 |
|---|---|
| `--log-driver awslogs` | 컨테이너 로그를 CloudWatch로 전송 |
| `if run_container ...; else` | `set -e` 때문에 `docker run` 실패 시 롤백 없이 종료되어 **서비스가 내려가던 약점** 제거 |

**E. 로그 읽기: 서버가 아니라 CloudShell에서**

```bash
aws logs tail /ticketing/back --region ap-northeast-2 --since 10m
aws logs tail /ticketing/back --region ap-northeast-2 --follow       # Ctrl+C 로 종료
```

Logs Insights (콘솔: CloudWatch → 로그 → Logs Insights, 로그 그룹 `/ticketing/back`)

```
fields @timestamp, @message
| filter @message like /ERROR|Exception/
| sort @timestamp desc
| limit 20
```

확인한 것:

| 확인 | 결과 |
|---|---|
| `docker inspect ticketing-back --format '{{.HostConfig.LogConfig.Type}}'` | `awslogs` |
| `docker logs --tail 5 ticketing-back` | 로그 드라이버를 써도 서버에서 읽힘 (`deploy.sh` 의 실패 진단 출력이 동작함) |
| CloudWatch 로그 스트림 | **컨테이너마다 스트림이 따로 생김.** 교체되어 삭제된 이전 컨테이너의 로그도 남아 있음 |
| 앱 시작 로그 | `HikariPool-1 - Start completed`, `Database version: 8.4.9` (RDS 연결 증거), `Started TicketingBackApplication` |

- **서버 역할에는 로그를 읽는 권한을 주지 않았습니다.** 서버가 침해되어도 로그를 읽거나 지울 수 없게 하는 최소 권한 설계라서, 서버 터미널에서 `aws logs tail` 을 실행하면 `AccessDenied` 가 나는 것이 정상입니다. 읽는 쪽은 CloudShell(`ticketing-dev`)입니다.
- 로그에서 `Initializing Spring DispatcherServlet` 은 **첫 HTTP 요청이 들어온 시점**에 찍힙니다. 배포 직후의 헬스체크 요청과 시각이 맞습니다.
- 로그 레벨을 `DEBUG` 로 올리면 SQL 파라미터 등 민감한 값이 찍힐 수 있으므로 주의합니다. CloudWatch는 수집량과 보관량에 따라 과금되므로 청구서의 CloudWatch 항목을 같이 봅니다.

### 13-6. 학습이 끝났을 때 리소스 정리 순서

> **삭제는 되돌릴 수 없습니다.** 학습이 모두 끝났을 때 아래 순서로 진행합니다. 순서에는 이유가 있습니다(의존 관계: 리소스를 쓰고 있는 것부터 지움).

| 순서 | 대상 | 비고 |
|---|---|---|
| 1 | 필요한 데이터가 있으면 RDS **최종 스냅샷** 생성 | 스냅샷도 보관량에 따라 과금되므로 불필요하면 건너뜀 |
| 2 | **EC2 인스턴스 종료**(terminate) | 중지(stop)가 아니라 종료. 루트 디스크(EBS)도 함께 삭제되는지 확인 |
| 3 | **RDS 삭제** | 삭제 방지는 이미 해제 상태. 최종 스냅샷 생성 여부와 **자동 백업 삭제** 여부 선택 |
| 4 | **탄력적 IP 해제** (사용했다면) | 연결 안 된 탄력적 IP는 과금 |
| 5 | **보안그룹 삭제**: `ticketing-db-sg` 먼저, 그다음 `ticketing-sg` | `ticketing-db-sg` 규칙이 `ticketing-sg` 를 참조하므로 순서를 거꾸로 하면 삭제 거부 |
| 6 | **ECR 저장소 삭제** (이미지 포함) | |
| 7 | **CloudWatch 로그 그룹** 삭제: `/ticketing/back`, `RDSOSMetrics` | |
| 8 | **Parameter Store** 삭제: `/ticketing/prod/*`, `/config/ticketing-back_prod/*` | |
| 9 | **IAM 정리**: `ticketing-ec2-role`(인스턴스 프로파일 포함), `github-actions-deploy`, `rds-monitoring-role`, OIDC 공급자, 사용자 `ecr-push-local`(액세스 키 포함) | `ticketing-dev` 는 마지막 |
| 10 | **GitHub**: 저장소 Variables(`AWS_ROLE_ARN`, `EC2_INSTANCE_ID`) 삭제, 필요하면 워크플로 비활성화 | 배포 잡이 실패하는 것을 막음 |
| 11 | 확인: Tag Editor 에서 **모든 리전**의 남은 리소스 점검, 다음 날 청구서 확인 | Budgets 알림은 청구서가 0원으로 확인될 때까지 유지 |

---

## 14. 진행 현황

- [x] Step 0. 로컬 `bootRun` + Swagger 확인
- [x] Step 1. Git / GitHub 저장소 연결
- [x] Step 2. Dockerfile 작성, 로컬 컨테이너 실행, 레이어 캐시 실험
- [x] Step 3. AWS 수동 배포 (ECR, EC2, 보안그룹, IAM 역할, Swagger 접속 확인)
- [x] Step 4. CI: GitHub Actions 빌드, 브랜치 보호 ruleset
- [x] Step 5. CD: OIDC → ECR → SSM 배포, 헬스체크, 롤백
- [x] Step 6. RDS 연결 + 비밀 분리(Parameter Store) + 롤백 대상 개선
- [x] Step 7. 운영 습관 (켜고 끄는 루틴, RDS 설정 점검, 비용 예산, CloudWatch 로그, 정리 순서 문서화)
- [ ] Step 8~ 티케팅 기능 (재고/동시성 → Redis → Kafka → SSE/대기열)

남은 정리 항목:

- [ ] ECR 수명 주기 정책 적용 확인 (13-3)
- [ ] 10-10 체크리스트: 롤백 실험 설정 되돌리기, `ecr-push-local` 액세스 키 삭제, 디버그 브랜치 삭제, 워크플로 경고 정리(`ubuntu-24.04` 고정, `setup-java@v5`, 로그 출력 단계 조건)
- [ ] 11-3 TODO: 소스에서 Parameter Store를 읽는 방식 기록
- [ ] 예산: 크레딧 제외 옵션과 예측 알림 확인 (13-4)
