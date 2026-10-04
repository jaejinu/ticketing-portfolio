# 자산 출처와 공개 범위

프로젝트 자체의 오픈소스 라이선스는 소유자 선택에 따라 미지정으로 유지합니다.
아래 외부 자산의 라이선스와 프로젝트 자체의 라이선스는 별개입니다.

| 항목 | 구현 위치 | 출처·고지 |
| --- | --- | --- |
| IBM Plex Sans KR | `frontend/app/layout.tsx`, `next/font/google` | [Google Fonts 원본](https://github.com/google/fonts/tree/main/ofl/ibmplexsanskr), Copyright IBM Corp., SIL OFL 1.1 |
| Instrument Serif | 같은 위치 | [Google Fonts 원본](https://github.com/google/fonts/tree/main/ofl/instrumentserif), Instrument Serif Project Authors, SIL OFL 1.1 |
| 공연 포스터 7종 | `frontend/public/posters/*.svg` | 저장소의 프로젝트용 SVG 원본. 외부 사진 파일은 사용하지 않음 |
| 문서 화면 캡처 | `docs/images/*.png` | 이 앱을 Playwright fixture 공연·계정으로 실행한 화면 |
| 런타임·개발 패키지 | `frontend/pnpm-lock.yaml`, `backend/gradle/libs.versions.toml` | 각 패키지의 원저작자·라이선스 적용. 전체 전이 의존성의 라이선스 적합성 검토는 별도 |

폰트 원문 고지는 `frontend/public/licenses/`에 보관하며 빌드된 앱의 `/licenses/` 경로에도 포함됩니다.
디자인 참고 서비스의 상표·유료 템플릿을 사용할 권리를 이 문서가 부여하지 않습니다.
소스 검사에서는 외부 사진·폰트 바이너리·유료 템플릿 배포 파일을 확인하지 않았으며,
별도 디자인 계약이나 비공개 참고 자료의 권리 관계까지 확인한 것은 아닙니다.
