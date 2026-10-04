/**
 * 이 파일의 책임: ESLint flat config (v9).
 *
 * Next 16 의 `eslint-config-next` 는 flat config 배열을 그대로 export 한다.
 * 우리는 위에 우리 룰 override 를 한 객체 더 얹는데, flat config 의 규칙상
 * 룰 이름의 plugin prefix (`@typescript-eslint/...`) 를 쓰려면 같은 객체에
 * plugins 도 명시해야 한다.
 */

import next from 'eslint-config-next';
import tseslint from 'typescript-eslint';

const config = [
  ...next,
  {
    files: ['**/*.{ts,tsx}'],
    plugins: {
      // next 가 typescript-eslint 의 룰을 enable 만 했고 plugin 등록은
      // 다른 객체에 있으므로, 우리가 override 하는 객체에서 명시적으로 묶어준다.
      '@typescript-eslint': tseslint.plugin,
    },
    rules: {
      // placeholder 컴포넌트가 prop 을 안 쓰는 경우가 있어 warn 으로 완화.
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // any 는 금지. placeholder 라도 unknown 또는 구체 타입을 쓴다.
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    ignores: [
      '.next/**',
      '.next-lan/**',
      'node_modules/**',
      'next-env.d.ts',
      'out/**',
      'public/**',
    ],
  },
];

export default config;
