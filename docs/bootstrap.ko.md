<!-- translated-from: bootstrap.md@00bde70d05da88a41a6231ab1eb95c52dfac1186 -->

# 부트스트랩 — 아무것도 없는 상태에서 첫 기록까지

**[English](bootstrap.md)**

깨끗한 기계에서 시작해, 문제를 지켜보다가 제출하면 기록을 쓰는 서버까지 데려갑니다. 이 저장소를
한 번도 본 적 없다고 가정합니다.

해당되지 않는 단계는 그렇다고 적어두었습니다. 선택 사항처럼 보인다고 건너뛰지 마십시오.

> **시작하기 전에 읽으십시오.** 마지막 절 *[아직 할 수 없는 것](#아직-할-수-없는-것)* 이 오늘
> 진짜로 없는 것들을 나열합니다. 아래 내용이 그것을 감추지는 않지만, 시간을 들이기 전에 전체
> 모양을 아는 편이 좋습니다. 가장 짧게 말하면: **서버가 모르는 문제는 아무것도, 조용히 기록되지
> 않습니다.** 센서 확장은 그 부담을, 그것이 로드된 브라우저 프로필에서만 없애줍니다.

---

## 0. 이게 실제로 무엇인가

**상주하는 로컬 프로세스** 입니다. 프로그래머스에서 문제를 푸는 동안 계속 떠 있으면서, 브라우저가
구독하는 것과 같은 채점 채널을 구독하고, 모든 `run` 과 `submit` 을 — 실패까지 포함해 — 내가
소유한 git 저장소에 씁니다.

대신 제출해 주는 일은 절대 없고, 브라우저가 이미 보내지 않는 것은 프로그래머스에 아무것도 보내지
않습니다.

두 가지는 이 프로세스 바깥에 있고, 내 것이어야 합니다:

| | 무엇인가 | 어디에 있나 |
|---|---|---|
| **기록 저장소** | 내 풀이 이력. 평범한 git 저장소. | 내가 고른 경로, 이 저장소 바깥 |
| **세션 쿠키** | 내 프로그래머스 로그인. 자격 증명. | 내가 만드는 파일, 절대 커밋되지 않음 |

---

## 1. 설치

하나를 고르십시오. 결과는 같고, Docker 쪽이 더 짧습니다.

**Docker** — macOS나 Windows에서는 [Docker Desktop](https://www.docker.com/products/docker-desktop/),
Linux에서는 Docker Engine + Compose 플러그인. 그 외에는 필요 없습니다. JDK도 필요 없습니다.

```bash
docker --version && docker compose version
```

**네이티브** — JDK 25 ([Temurin](https://adoptium.net/temurin/releases/?version=25))와 `PATH` 위의
`git`. 나머지는 Gradle 래퍼가 처리합니다.

```bash
java -version   # 25 여야 합니다
git --version
```

그다음 이 저장소를 클론합니다. 아래는 전부 저장소 루트에서 실행합니다.

```bash
git clone https://github.com/BrokenFinger98/programmers-tracker.git
cd programmers-tracker
```

---

## 2. 기록 저장소 — 서버가 만듭니다

이건 여러분의 데이터입니다. 일부러 이 저장소 **안에 있지 않습니다** — 풀이 기록은 도구의
저장소에 절대 들어가지 않고, 분리되어 있어야 도구는 공개로 두고 기록은 비공개로 만들 수
있습니다.

**실행할 게 없습니다.** 첫 시작 때 서버가 디렉토리를 만들고(`.env` 의 `TRACKER_RECORD_REPO`
가 없으면 `~/ps-records`), `git init` 을 하고, README와 Obsidian 대시보드와 ignore 규칙을
심습니다. 시작 로그에 `Records live at <경로>` 가 찍혀서 위치가 조용히 정해지는 일은
없습니다.

기계 밖 백업을 원하면 `.env` 에 GitHub 토큰을 넣으세요:

```bash
# .env
GITHUB_TOKEN=github_pat_…
```

**권한**: classic 토큰은 `repo` 스코프 하나면 됩니다. fine-grained 토큰은 *All repositories*
접근에 *Administration: read and write*(생성)와 *Contents: read and write*(푸시)가 필요하고 —
그래도 GitHub이 생성을 거부하면 로그에 그렇다고 찍히니 classic을 쓰세요. 나중에 조이려면 이
저장소 하나에만 스코프된 Contents 전용 토큰으로 갈아끼우고 재시작하면 됩니다 — 토큰이 있는
부팅마다 저장된 자격증명이 갱신됩니다.

다음 시작 때 서버가 GitHub에 토큰의 주인이 누군지 묻고, 기록 디렉토리 이름의 **비공개**
저장소를 만들고(비공개는 하드코딩이며 응답에서 재검증합니다 — GitHub이 공개 저장소로
답하면 아무것도 연결하지 않습니다), `origin` 으로 등록하고, 자격증명을 기록 저장소의
gitignore된 `.ps/` 안에 소유자 전용으로 저장한 뒤 푸시합니다. 그 첫 부팅 후에는 `.env` 의
줄을 지워도 됩니다 — 저장된 자격증명이 이후 푸시를 담당합니다. 토큰을 revoke하면 새로 넣기
전까지 푸시가 멈춥니다.

이미 있는 저장소는 — 직접 init했든, 어떤 remote가 걸려 있든 — 절대 다시 연결하지 않습니다:
서버는 없는 것만 추가하고 있는 것은 바꾸지 않습니다. GitHub이 아닌 remote를 쓰려면 토큰 없이
직접 `git remote add origin <url>` 하고, 그 호스트가 요구하는 자격증명을 쓰세요.

**예전에 SSH로 푸시하던 설치라면?** remote를 HTTPS로 바꾸고
(`git -C ~/ps-records remote set-url origin https://github.com/<you>/<repo>.git`), `.env` 에
토큰을 넣고 재시작한 뒤, `compose.override.yaml` 의 옛 키 마운트를 지우세요 — SSH는 #258에서
폐기됐습니다.

remote를 아예 안 써도 됩니다. 기록은 여전히 쓰이고 로컬에 커밋됩니다. 잃는 건 푸시뿐이고,
그마저 버려지지 않고 재시도됩니다.

## 3. 세션 쿠키 얻기

서버는 나로서 프로그래머스 채점 채널을 구독합니다. 쿠키 하나, `_session_production` 이 필요합니다.

1. 브라우저에서 <https://school.programmers.co.kr> 에 로그인합니다.
2. DevTools를 엽니다 (`F12`, macOS는 `Cmd+Option+I`).
3. **Application**(Chrome) 또는 **Storage**(Firefox) → **Cookies** →
   `https://school.programmers.co.kr`.
4. `_session_production` 을 찾아 **Value** 를 복사합니다.

`HttpOnly` 라서 콘솔의 `document.cookie` 로는 보이지 않습니다. DevTools가 유일한 방법입니다.

`.ps/session` 에 씁니다:

```bash
mkdir -p .ps
printf '%s' 'PASTE_THE_VALUE_HERE' > .ps/session
chmod 600 .ps/session
```

`.ps/` 는 통째로 gitignore 되어 있고, 그 아래 무엇이든 커밋되면 저장소 가드가 빌드를 실패시킵니다.
파일에는 값만 넣습니다. `_session_production=` 접두사는 도구가 직접 붙입니다.

**비밀번호처럼 다루십시오.** 이것이 내 로그인입니다. 만료됩니다 — 만료되면 구독이 실패하기
시작하고, 이 단계를 다시 하게 됩니다.

---

## 4. 서버 시작

### Docker로

```bash
cp .env.example .env
```

`.env` 를 편집합니다. 두 가지는 필수이고 기본값이 없습니다. 우리가 추측할 수 있는 것이 아니기
때문입니다:

```bash
TRACKER_RECORD_REPO=/absolute/path/to/ps-records   # 2단계에서 만든 경로, 전체 경로로 — ~ 는 확장되지 않습니다
GIT_AUTHOR_NAME=Your Name
GIT_AUTHOR_EMAIL=you@example.com
```

git은 신원 없이는 커밋을 거부하므로, 비워두면 디스크에는 기록하고 커밋은 영원히 안 하는 서버를
얻게 됩니다. Compose는 그런 서버를 띄우는 대신 메시지를 내고 멈춥니다.

**Linux에서만**, 컨테이너가 기록 디렉터리에 쓸 수 있도록 소유자를 맞춰야 합니다 — macOS와
Windows의 Docker Desktop은 소유권을 재매핑하므로 둘 다 필요 없습니다:

```bash
echo "TRACKER_UID=$(id -u)" >> .env
echo "TRACKER_GID=$(id -g)" >> .env
```

그다음:

```bash
docker compose up -d --build
docker compose logs -f
```

> **`--build` 는 선택이 아니라, 원하는 기본값입니다.**
> 그냥 `docker compose up` 은 이미 그 태그를 달고 있는 이미지를 재사용하고 **다시 빌드하지
> 않습니다.** 그래서 지난번에 빌드한 코드를 계속 돌리게 되고, 증상은 "조용히 일어나지 않는 동작"
> 이거나 "현재 코드에는 없는 필드를 지목하는 오류" 입니다.
> 2026-08-10 실측: 갓 작성한 확장이 나흘 묵은 컨테이너에 대고
> `400 INVALID_REQUEST — challengeableId is missing` 를 받았습니다.
> 첫 로그 줄이 어느 빌드인지 알려줍니다:
>
> ```
> Running build 0.0.1-SNAPSHOT — compiled 2026-08-06 15:32:45 KST from commit unknown.
> ```
>
> 그 타임스탬프가 마지막 pull보다 이르면, 낡은 이미지를 돌리고 있는 것입니다.

### 네이티브로

```bash
export TRACKER_RECORD_REPO=~/ps-records
./gradlew bootRun
```

### 포트에 대해, 그리고 알아둘 만한 것 하나

서버는 **내 기계의 루프백 인터페이스 8080 포트에서만** 듣습니다. 네트워크의 어떤 것도 닿을 수
없습니다. 이것은 평소보다 더 중요합니다: 이 프로세스는 살아 있는 세션 쿠키를 메모리에 들고 있고
내 GitHub에 푸시할 수 있습니다.

8080이 이미 쓰이고 있다면 `TRACKER_PORT` 로 바꾸십시오 — Docker는 `.env` 에서, 네이티브 실행은
환경 변수로.

> **컨테이너 네임스페이스 안의 바인드 주소는 호스트의 바인드 주소와 같은 통제가 아닙니다.**
> 컨테이너는 자기 네트워크 스택을 가지며, `docker -p` 는 컨테이너의 이더넷 주소로 포워딩할 뿐
> 루프백으로는 절대 포워딩하지 않습니다 — 그래서 컨테이너 안에서 `127.0.0.1` 에 바인드한 서버는
> 보호되는 게 아니라 아예 브라우저에서 닿지 않습니다. 그래서 `compose.yaml` 은 컨테이너 *안에서는*
> `0.0.0.0` 에 바인드하고 포트를 `127.0.0.1:8080:8080` 으로 공개합니다. `/watch` 를 LAN에서
> 떼어놓는 것은 그 **공개(publish)** 주소입니다. 이것을 `8080:8080` 으로 줄이면 전부 노출한
> 것입니다.
>
> 남는 위험을 그대로 적자면: 이 컨테이너와 같은 Docker 네트워크를 공유하는 다른 컨테이너는 공개
> 주소와 무관하게 닿을 수 있습니다. 이 서비스는 자기 전용 네트워크에서 돌고 아무도 합류하지
> 않으며, 다음 단계의 토큰이 두 번째 층입니다.
>
> 전체 근거: [컨테이너 네트워크 태세 ADR](llm-wiki/wiki/decisions/2026-08-06-container-network-posture.md).

---

## 5. watch 토큰 찾기

루프백은 내 기계의 다른 모든 프로그램, 그리고 브라우저에 열린 모든 페이지와 공유됩니다. 그래서
명령을 받는 유일한 엔드포인트인 `POST /watch` 에는 토큰이 필요합니다.

**직접 만들지 않습니다.** 첫 시작 때 서버가 256비트 토큰을 생성해 소유자만 읽을 수 있게 저장하고,
어디에 저장했는지 말해줍니다:

```
INFO ... c.b.tracker.adapter.web.WatchToken : Generated a local /watch token at .ps/watch-token — paste it into the extension.
```

읽습니다:

```bash
cat .ps/watch-token
```

재시작을 넘어 유지되는 것은 의도된 것입니다 — 매번 바뀌는 토큰은 모든 하트비트를 조용히
거부합니다. 값은 절대 로그에 찍히지 않고 경로만 찍힙니다. 직접 정하고 싶으면
`TRACKER_WATCH_TOKEN` 을 설정하십시오. 검사를 끄는 방법은 없습니다.

---

## 6. 지금 어느 문제를 푸는지 서버에 알리기

서버는 알려준 채널만 관찰합니다. 그래서 이 단계가 제출을 기록되게 만드는 바로 그 단계입니다.

### 센서 확장으로

[`extension/`](../extension/README.ko.md) 을 압축 해제 상태로 설치합니다 — `chrome://extensions`
→ 개발자 모드 → **압축해제된 확장 프로그램을 로드합니다** — 그다음 옵션을 열고 5단계의 토큰을
붙여넣습니다. 그 뒤로는 여는 모든 문제를 알리고, 언어 탭을 바꾸면 다시 알리고, 서버 재시작 후에도
스스로 다시 등록합니다. 툴바 배지가 서버가 받았는지를 말해줍니다: 초록은 감시 중, 주황은 아직
토큰 없음, 빨강은 서버가 준 오류를 담고 있습니다.

2026-08-10에 로드해서 동작을 눈으로 확인했습니다 — 살아 있는 문제 페이지에 대고 배지가
`watching lesson 181947 in java (refreshed)` 를 표시했습니다. 무엇이 실측되었고 무엇이 아닌지는
확장의 README에 정확히 적혀 있습니다.

### 확장 없이, 손으로

DevTools는 필요 없습니다 — 서버가 스스로 알아낼 수 없는 두 가지는 문제 번호(URL의 마지막 부분)와
열어둔 언어 탭뿐입니다. 나머지는 문제 페이지에서 직접 읽습니다.

```bash
curl -X POST http://127.0.0.1:8080/watch \
  -H "X-Tracker-Token: $(cat .ps/watch-token)" \
  -H 'Content-Type: application/json' \
  -d '{"lessonId":120803,"language":"java"}'
```

확장을 설치했더라도 알아둘 값어치가 있습니다: 배지가 예상 밖의 말을 할 때 서버를 직접 확인하는
방법이 이것입니다.

성공하면 이렇게 나옵니다:

```json
{"status":"started","lessonId":120803,"language":"java"}
```

`{"status":"refreshed",...}` 는 이미 그 문제를 보고 있었다는 뜻입니다. 반복 호출은 안전합니다.

언어 탭을 바꿀 때(다른 탭은 다른 채널입니다)와 서버를 재시작한 뒤에 다시 보내십시오. 네, 수동
작업입니다 — [아직 할 수 없는 것](#아직-할-수-없는-것) 참고.

---

## 7. "동작한다" 는 게 어떤 모습인가

**시작할 때**, 로그가 이 순서로 끝납니다:

```
Tomcat started on port 8080 (http) with context path '/'
Started TrackerApplicationKt in 1.046 seconds
Startup reconciliation: ReconcileReport(recorded=0, duplicates=0, failed=0, skippedLines=0)
```

Docker라면 `docker compose ps` 가 1분 안에 컨테이너를 `healthy` 로 표시합니다.

6단계에서 등록한 문제에서 **채점하기를 누르면**, 서버가 채점을 실시간으로 관찰하고 — 결과가
확정되면 — 기록 저장소에 씁니다:

```
ps-records/
├── log/submissions.jsonl                        채점 하나당 한 줄, 시도 번호의 authority
└── problems/120804-<title>/
    ├── README.md                                시도 이력
    └── attempts/
        ├── 001.java                             내가 제출한 코드
        └── 001.raw.jsonl                        원본 프레임, 그대로 보존
```

확인:

```bash
cd "$TRACKER_RECORD_REPO" && git log --oneline -3 && tail -1 log/submissions.jsonl
```

통과한 `submit` 은 즉시 커밋되고 푸시됩니다. 그 외에는 커밋만 되고 일일 백업이 함께 올려
보냅니다(`TRACKER_BACKUP_ZONE` 기준 23:00, 기계가 자고 있었다면 다음 시작 때 따라잡습니다).

**`run` 은 attempt 파일을 쓰지 않습니다.** 정상입니다 — run은 다음 submit에 함께 실려 갑니다.

### 동작하지 않을 때

| 보이는 것 | 뜻 |
|---|---|
| `401 {"error":"UNAUTHORIZED"}` | `X-Tracker-Token` 이 틀렸거나 없습니다. `.ps/watch-token` 을 다시 읽으십시오. |
| `400 {"error":"INVALID_REQUEST","field":"..."}` | 그 필드가 페이지에 없었습니다. 프로그래머스가 마크업을 바꿨거나, 문제 페이지가 아닌 곳에서 실행했습니다. |
| `503 {"error":"WATCHER_SATURATED"}` | 이미 여덟 문제를 보고 있고 전부 채점 중입니다. 기다리거나 재시작하십시오. |
| `curl: (7) Failed to connect` | 서버가 안 떠 있거나 `TRACKER_PORT` 가 다릅니다. |
| `Session file not found` | 3단계를 건너뛰었거나 `TRACKER_SESSION_FILE` 이 다른 곳을 가리킵니다. |
| 시작할 때 `not a git repository` 경고 | `TRACKER_RECORD_REPO` 가 git 저장소를 가리키지 않습니다. 기록은 쓰이지만 커밋은 되지 않습니다. |
| `git push failed with 128: ... No configured push destination` | 리모트가 없습니다. 2단계의 그 부분을 건너뛰었다면 정상입니다. |
| `git reconcile failed with 128: Author identity unknown` | `GIT_AUTHOR_NAME` / `GIT_AUTHOR_EMAIL` 이 설정되지 않았습니다. 기록은 쓰이지만 커밋되지 않습니다. |
| 제출했는데 아무것도 기록되지 않음 | `/watch` 로 문제가 등록되지 않았거나, 그 순간 서버가 죽어 있었습니다. **그 채점은 사라졌습니다** — 설계상 사후 복구가 불가능합니다. |

---

## 아직 할 수 없는 것

시행착오로 알아내는 것보다 낫기 때문에 그대로 적습니다.

- **등록하지 않은 문제는 아무것도, 조용히 기록되지 않습니다.** 센서 확장
  ([`extension/`](../extension/README.ko.md))이 그 부담을 없애려고 존재하고 2026-08-10에
  브라우저에서 검증되었지만, 로드된 페이지만 알립니다. 배지가 초록이 아니면 아무것도 감시되고 있지
  않습니다 — 6단계의 수동 경로를 손 닿는 곳에 두십시오.
- **MCP 서버는 설계의 스무 개가 아니라 여섯 개의 툴을 노출합니다.** `submissions`,
  `get_problem`, `stats`, `list_problems`, `review_queue`, `slow_passes` 가 오늘 만들어져 연결
  가능합니다 — 클라이언트 설정은 [`mcp.ko.md`](mcp.ko.md) 참고. 아직 없는 것은 분석 절반의
  나머지입니다: 워밍업 진단, 시험 모드, 회사별 프로필, 그리고 쓰기를 하는 모든 것.
- **푸시는 `GITHUB_TOKEN` 으로 인증합니다** — 서버가 기록 저장소의 gitignore된 `.ps/` 안에
  소유자 전용으로 저장하고 git이 거기서 읽습니다. 마운트할 것이 없습니다. 토큰이 없으면
  커밋은 로컬에 계속 쌓이고 푸시만 빠집니다.

- **같은 기록 저장소에 컨테이너와 네이티브 인스턴스를 동시에 돌리지 마십시오.** 두 번째
  인스턴스는 시작을 거부합니다. 두 가지 장치가 강제합니다: 배타적 파일 락(#44), 그리고 —
  **Docker Desktop이 bind mount에서 파일 락을 지키지 않기 때문에**, 그리고 그것이 정확히
  `compose.yaml` 이 컨테이너에 기록을 넘기는 방식이기 때문에 — 그 뒤에 놓인 생존 마커(#52),
  그 마운트 위에서 검증되었습니다. 거부 메시지는 둘 중 어느 쪽이 거부했는지 말합니다. 회복
  방법이 다르기 때문입니다. 한 저장소에 두 writer가 붙으면 시도 번호가 깨지고 git 인덱스를 두고
  다투므로, 정확히 하나만 돌리십시오. 상세와 아직 검증되지 않은 것(Linux 호스트, Windows,
  네트워크 파일시스템):
  [`decisions/2026-08-06-record-repository-lock`](llm-wiki/wiki/decisions/2026-08-06-record-repository-lock.md).
- **`.ps/` 도 락이 덮습니다. 기록 저장소 안에 있기 때문입니다.** 원본 프레임, 문제별 타이머,
  백업 마커가 `<내 기록>/.ps/` 에, 자신이 설명하는 기록 옆에 있으므로, 저장소를 점유하면 그것들도
  함께 점유됩니다. `.gitignore` 에 `.ps/` 가 없으면 서버가 시작할 때 직접 추가합니다 — **이
  변경 이전에 만들어진 저장소는 상태 파일을 하나씩 나열하고 있어 이것들을 전혀 무시하지
  못하며**, 그 규칙이 없으면 `git add --all` 이 수집 이력 전체를 두 번째로 커밋합니다. 바깥에
  남는 것은 생성된 `/watch` 토큰뿐입니다. 자격 증명이고, 기록 저장소는 푸시되기 때문입니다.
- **시각은 `TZ` 를 따르고, 설정하지 않으면 UTC입니다.** 기록에 찍히는 시계이자 일일 백업이 쓰는
  시계이므로, 볼트의 시도 이력이 몇 시간씩 어긋나 보인다면 그건 이 설정입니다. 서버는 매 시작마다
  자기가 정한 시간대를 출력합니다. `.env` 에 `TZ` 를 넣으십시오. `TRACKER_BACKUP_ZONE` 은 기록하는
  시각과 다른 시각에 푸시하고 싶을 때만 씁니다.

---

## 다음으로 볼 곳

- [`README.ko.md`](../README.ko.md) — 이 도구가 무엇을 해결하고 기존 도구와 어떻게 다른지
- [`docs/programmers-protocol.md`](programmers-protocol.md) — 프로토콜, 그리고 위 모든 주장의
  실측 근거 (영어)
- [`docs/llm-wiki/index.md`](llm-wiki/index.md) — 모든 결정과 그 이유 (영어)
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — 이슈 우선, 스쿼시 전용, 그리고 기여자가 쓰는 모든
  것은 영어로 (여섯 페이지가 한국어 쌍을 가집니다 —
  [결정 문서](llm-wiki/wiki/decisions/2026-08-11-korean-for-the-user-facing-half.md))
