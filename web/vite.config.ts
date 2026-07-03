import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Served from https://rahadalways.github.io/camconnect/
// If you later point a custom (sub)domain at it, change base to "/".
export default defineConfig({
  base: "/camconnect/",
  plugins: [react(), tailwindcss()],
});
