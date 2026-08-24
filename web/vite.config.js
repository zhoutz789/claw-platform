import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 本地开发：浏览器同源访问 /api，由 Vite 代理到后端 :8080，规避 CORS。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
});
