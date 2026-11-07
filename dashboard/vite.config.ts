import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// `npm run dev` proxies API calls to the Compose stack (nginx on :3000 by default).
const target = process.env.API_PROXY ?? "http://localhost:3000";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": { target, changeOrigin: true }, "/grafana": { target, changeOrigin: true } },
  },
});
