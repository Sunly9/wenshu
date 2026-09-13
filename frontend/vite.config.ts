import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import AutoImport from 'unplugin-auto-import/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  server: {
    port: 5173,
    host: true,
    allowedHosts: true,
    proxy: { '/api': 'http://localhost:8080' },
  },
  preview: {
    port: 5173,
    host: true,
    allowedHosts: true,
    proxy: { '/api': 'http://localhost:8080' },
  },
})
