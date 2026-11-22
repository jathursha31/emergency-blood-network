import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev:   npm run dev   -> http://localhost:5173, /api calls are proxied to Spring Boot on :8080
// Build: npm run build -> writes the production bundle into Spring Boot's static folder,
//                        so `mvnw spring-boot:run` serves the React app at http://localhost:8080
export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            '/api': 'http://localhost:8080',
        },
    },
    build: {
        outDir: '../src/main/resources/static',
        emptyOutDir: true,
    },
});
