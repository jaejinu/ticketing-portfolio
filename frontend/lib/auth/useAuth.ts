/**
 * 이 파일의 책임: `useAuth` 훅 재노출.
 *
 *  AuthProvider 와 같은 파일에 두면 'use client' 경계 때문에 서버 컴포넌트가
 *  훅을 import 할 때 함께 client 로 전환되어버린다. 분리해서 import 안전성을
 *  확보한다. 구현은 AuthProvider.tsx 안에서 그대로 export.
 */

export { useAuth } from './AuthProvider';
