# JWT RSA 키 디렉터리

이 디렉터리에는 **로컬 개발용** RSA 2048 키 페어가 위치합니다.

## 자동 생성

`application-local.yml` 의 `app.auth.jwt.auto-generate-keys: true` 가 활성화되어 있으면
backend 가 처음 부팅될 때 `JwtKeyManager` 가 키 파일이 없는 경우 자동으로 RSA 2048 키 페어를
PKCS#8 PEM 형식으로 생성해 이 디렉터리에 저장합니다.

```
backend/module-auth/src/main/resources/keys/
├── jwt-private.pem    # PKCS#8 PRIVATE KEY
└── jwt-public.pem     # X.509 PUBLIC KEY (SubjectPublicKeyInfo)
```

## gitignore

이 디렉터리의 `*.pem` 파일은 루트 `.gitignore` 의 `*.pem` 룰로 자동 무시됩니다.
실수로 커밋하지 마세요 — 만약 commit 했다면 `git filter-repo` 로 히스토리에서 제거하고
즉시 새 키로 교체하세요.

## 수동 생성 (선호 시)

```bash
# private key (PKCS#8)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out backend/module-auth/src/main/resources/keys/jwt-private.pem

# public key (X.509)
openssl rsa -pubout \
  -in  backend/module-auth/src/main/resources/keys/jwt-private.pem \
  -out backend/module-auth/src/main/resources/keys/jwt-public.pem
```

## 운영 환경

운영에서는 `auto-generate-keys: false` 로 두고, 외부 KMS / 시크릿 매니저에서 주입받은 PEM 파일을
컨테이너 볼륨/시크릿으로 마운트하는 것을 권장합니다. 키 로테이션은 새 키를 먼저 추가해 JWKS 로
노출 → 충분한 grace 후 옛 키 제거의 2-단계로 진행합니다.
