import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Served at the root of the custom domain (https://fonocam.toolastic.com/)
// on Cloudflare Pages, so base is "/".
export default defineConfig({
  base: "/",
  plugins: [react(), tailwindcss()],
});
