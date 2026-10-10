import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8080' },
      '/actuator': { target: process.env.VITE_BACKEND_URL ?? 'http://localhost:8080' },
    },
  },
})
