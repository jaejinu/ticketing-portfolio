# braces 중첩 깊이 방어

`braces@3.0.3.patch`는 개발 도구 ESLint의 전이 의존성에 적용하는 프로젝트 임시 방어다.
[GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)의
과도한 중첩 AST 재귀 문제를 대상으로 한다. 2026-10-16 확인 당시 공식 수정 버전은 없다.
패키지 버전이나 audit 결과를 변경하거나 경고를 무시 목록에 넣지 않는다.

- parse는 brace와 parenthesis 스택 깊이를 제한한다.
- compile/expand/stringify는 직접 전달한 AST에도 재귀 깊이 제한을 적용한다.
- 128 경계에 도달하면 `BRACES_MAX_DEPTH` 접두사의 `SyntaxError`를 발생시킨다.
  극도로 중첩된 정상 패턴도 거절하는 호환성 제한이 있다.
- 깊이와 무관한 조합 폭발 등 모든 자원 고갈 문제를 해결하는 패치는 아니다.
- `pnpm.patchedDependencies`와 lockfile에 묶여 frozen install 및 CI에도 적용된다.

검사: `node scripts/check-braces-guard.mjs`. ESLint에서 실제 사용하는 transitive package를
찾아 정상 패턴과 15개 공격 입력을 검사한다. 각 공격 검사는 별도 프로세스에서 5초 제한으로
실행하며, stack overflow나 다른 오류가 아닌 의도한 제한 오류인지 확인한다.

공식 수정 릴리스가 나오면 의존성을 갱신하고 이 로컬 패치와 patchedDependencies 항목을
제거한 뒤 정상 패턴·공격 입력·lint·build를 다시 검증한다. 개발 의존성 보안 경고는
공식 수정 버전 적용 여부와 함께 계속 추적한다.
