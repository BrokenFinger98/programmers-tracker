# ps-records

프로그래머스 풀이 기록. [programmers-tracker](https://github.com/BrokenFinger98/programmers-tracker) 가 생성한다.

**이 폴더를 Obsidian vault 로 열면** 대시보드·약점 분석·복습 큐를 GUI 로 볼 수 있다.
필요한 플러그인은 **Dataview** 하나뿐이다.

| 노트 | 내용 |
|---|---|
| `_dashboard.md` | 최근 제출 · 오늘 통계 |
| `_weakness.md` | 태그별 첫 제출 통과율 · verdict 분포 |
| `_review.md` | 복습 큐 |
| `_warmup.md` | 재활성화 진단 — 살아있음 / 흐릿함 / 죽음 |
| `_exam.md` | 기출 세트 진행 현황 |

## 구조

```
problems/<lessonId>-<제목>/
├── README.md      서버 생성 (frontmatter + 시도 이력). 매번 덮어씀
├── notes.md       오답 노트. 서버가 건드리지 않음
├── Solution.java
├── SolutionTest.java   IntelliJ 로 열어 디버깅
├── meta.json
└── attempts/      제출별 코드 + 결과 원본
log/submissions.jsonl   전체 제출 로그 (MCP 가 읽는 원본)
```

⚠️ `.ps/session` 은 세션 쿠키다. **절대 커밋하지 않는다.**
