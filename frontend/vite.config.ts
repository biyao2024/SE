import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'
const lanHttps = process.env.FACE_LAN_HTTPS === 'true'
const localHttp = process.env.FACE_LOCAL_HTTP === 'true' && !lanHttps
const https = lanHttps ? {
  cert: readFileSync(process.env.FACE_LAN_CERT!),
  key: readFileSync(process.env.FACE_LAN_KEY!)
} : undefined
export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, https, watch: { ignored: ['**/.test-output/**'] }, proxy: { '/api': {
    target: 'http://127.0.0.1:8080', changeOrigin: true, ws: true,
    configure(proxy) {
      // This proxy is only enabled on the loopback HTTP companion, never on LAN HTTPS.
      if (localHttp) proxy.on('proxyRes', response => {
        const cookies = response.headers['set-cookie']
        if (cookies) response.headers['set-cookie'] = cookies.map(cookie => cookie.replace(/;\s*Secure\b/gi, ''))
      })
    }
  } } },
  build: { rollupOptions: { output: { manualChunks: { charts: ['echarts'], ui: ['element-plus'], framework: ['vue', 'pinia', 'vue-router'] } } } }
})
