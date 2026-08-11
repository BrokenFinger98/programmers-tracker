<!-- translated-from: README.md@2b513e4d217b7e4daca7142ccb15e5d695e4bb4e -->

# ps-records

**[English](README.md)**

프로그래머스 문제 풀이 기록. [programmers-tracker](https://github.com/BrokenFinger98/programmers-tracker)가 생성합니다.

## 구조

매 run·submit 이후 서버가 씁니다:

```
problems/<lessonId>-<title>/
├── README.md          프론트매터 + 시도 이력. 매번 덮어쓰기됨
├── notes.md           내 오답 노트. 서버가 절대 건드리지 않음
├── Solution.<ext>     최신 코드, 언어당 한 파일
├── examples.json      채점기가 준 예제 입력과 기대 출력
├── runner_test.<ext>  그 예제로 만든 실행기 — 아래 참고
└── attempts/          제출별 코드(NNN.<ext>)와 원본 프레임(NNN.raw.jsonl)
log/submissions.jsonl  전체 제출 로그 (MCP 툴이 읽는 원본)
```

실행기는 java, python3, cpp, javascript, kotlin, c, csharp에 대해 생성됩니다. 다른 언어는
생성되지 않고 서버가 그 이유를 로그에 남깁니다. 실행 명령은 파일 자체의 헤더에 적혀 있고
(`java RunnerTest.java`, `python3 runner_test.py` 등), **매 run 이후 교체되므로 직접 고친
내용은 사라집니다.** C#은 옆에 `runner_test.csproj` 도 함께 생성됩니다.

## Obsidian 볼트로 보기

지금도 이 폴더를 볼트로 열어 문제별 페이지를 둘러볼 수 있습니다. 프론트매터에 태그·레벨·판정이
들어 있어 Obsidian의 검색과 그래프가 그대로 동작합니다.

설계에는 Dataview 기반의 생성 대시보드 노트 — `_dashboard`, `_weakness`, `_review`,
`_warmup`, `_exam` — 도 있습니다. **아직 만들어지지 않았으니** 찾지 마십시오. 생기면 이 문서에
목록이 실립니다.

⚠️ `.ps/` 는 트래커의 작업 상태입니다 — 아직 수집 중인 프레임, 문제별 타이머, 마지막 푸시가
언제 성공했는지. 자신이 설명하는 기록 옆에 있으라고 여기에 두었고, `.gitignore` 가 모든 커밋에서
빼냅니다. **절대 커밋하지 마십시오**: `.ps/raw/recorded/` 는 코드 실행 한 번당 파일 하나를
보관하는데, 한 문제를 푸는 동안 실행 버튼은 수십 번 눌리므로, 커밋하면 풀이 이력이 자기 자신의
연습 흔적에 파묻힙니다. `.gitignore` 가 이 규칙보다 오래된 경우 서버가 시작할 때 직접 추가합니다.
