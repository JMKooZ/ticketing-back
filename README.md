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

## 11. 진행 현황

- [x] Step 0. 로컬 `bootRun` + Swagger 확인
- [x] Step 1. Git / GitHub 저장소 연결
- [x] Step 2. Dockerfile 작성, 로컬 컨테이너 실행, 레이어 캐시 실험
- [x] Step 3. AWS 수동 배포 (ECR, EC2, 보안그룹, IAM 역할, Swagger 접속 확인)
- [x] Step 4. CI: GitHub Actions 빌드, 브랜치 보호 ruleset
- [x] Step 5. CD: OIDC → ECR → SSM 배포, 헬스체크, 롤백 (실험 완료)
- [ ] Step 6. 설정/비밀 분리(프로파일, Parameter Store) + RDS 연결
- [ ] Step 7. 운영 습관 (로그, 비용 알림, 리소스 정리)
- [ ] Step 8~ 티케팅 기능 (재고/동시성 → Redis → Kafka → SSE/대기열)
