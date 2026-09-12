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
    // 函数式 manualChunks：对 antd 做更细子包拆分（图标 / rc-* 底层组件 / antd 核心 / dayjs 各自独立）。
    chunkSizeWarningLimit: 1800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined;
          // antd 核心与其强耦合的 rc-* 底层组件、@ant-design/icons 必须同包：
          // 三者间存在 antd<->rc / antd<->icons 导入环，拆成独立 chunk 会触发 circular chunk 警告并可能引发初始化顺序问题。
          if (id.includes('@ant-design/icons') || id.includes('antd') || id.includes('@rc-component') || id.includes('/rc-') || id.includes('@rc-util')) return 'antd';
          if (id.includes('dayjs')) return 'dayjs';
          if (id.includes('react-router') || id.includes('react-dom') || id.includes('/react/')) return 'react';
          if (id.includes('i18next')) return 'i18n';
          return undefined;
        },
      },
    },
  },
});
