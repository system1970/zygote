import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Static build: base './' so dist/ works from any sub-path on the phone.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
  },
  server: {
    port: 5173,
  },
});
