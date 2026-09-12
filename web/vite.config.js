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
  build: {
    // 拆包：把体积最大的三方库从主 chunk 剥离，提升浏览器缓存命中与并行加载。
    chunkSizeWarningLimit: 1800,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
          antd: ['antd', '@ant-design/icons', 'dayjs'],
          i18n: ['i18next', 'react-i18next', 'i18next-browser-languagedetector'],
        },
      },
    },
  },
});
