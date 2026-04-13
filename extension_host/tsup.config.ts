// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

// tsup.config.ts
import { defineConfig } from 'tsup';

import { dependencies } from "./package.json";

export default defineConfig({
  entry: ['src/extension.ts'], // Your entry file
  format: ['esm'], // Output format, e.g., ES Module and CommonJS
  minify: true, // Minify code
  clean: true, // Clean output directory
  splitting: false,
  platform: 'node', // Target platform, e.g., Node.js
  target: 'node18', // Target environment, e.g., latest ECMAScript standard
  skipNodeModulesBundle: true, // Skip bundling node_modules to avoid bundling native modules
  external: [
    /^@vscode\/.*/,     // 所有 @vscode 原生模块
    'node-pty',          // 原生终端模块
    'kerberos',          // 原生认证模块
    '@parcel/watcher',   // 原生文件监视
    '@vscode/windows-mutex', // Windows 专用
    '@vscode/windows-process-tree', // Windows 专用
    'native-keymap',     // 原生键位映射
    'native-watchdog',   // 原生看门狗
  ],
  dts: false, // Don't generate type declaration files, as we usually handle type declarations separately
});
