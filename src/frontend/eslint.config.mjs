import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // android/ is the generated Capacitor project; its build intermediates include copies of the
  // web bundle, which are not ours to lint.
  globalIgnores([".next/**", "out/**", "build/**", "android/**", "next-env.d.ts"]),
]);

export default eslintConfig;
