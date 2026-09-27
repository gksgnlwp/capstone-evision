# capstone-evision

캡스톤 디자인 프로젝트

## 폴더 구조 (예정)

```
capstone-evision/
├── backend/    # 백엔드 서버
└── frontend/   # 프론트엔드
```

## 브랜치 규칙

| 브랜치 | 용도 |
| --- | --- |
| `main` | 발표/배포용 완성본. 직접 push 하지 않음 |
| `develop` | 개발 통합 브랜치. 기능 브랜치는 여기로 PR |
| `feature/기능명` | 각자 기능 작업 (예: `feature/login-api`) |

### 작업 흐름

1. `develop`에서 최신 코드 받기: `git switch develop` → `git pull`
2. 기능 브랜치 만들기: `git switch -c feature/기능명`
3. 작업 후 커밋: `git add .` → `git commit -m "feat: 로그인 API 추가"`
4. 올리기: `git push -u origin feature/기능명`
5. GitHub에서 `develop`으로 Pull Request 생성 → 리뷰 후 merge

### 커밋 메시지 규칙

- `feat:` 새 기능
- `fix:` 버그 수정
- `docs:` 문서 수정
- `refactor:` 리팩토링
- `chore:` 설정, 빌드 등 기타
