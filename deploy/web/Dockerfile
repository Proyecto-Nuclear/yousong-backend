# syntax=docker/dockerfile:1
# Owner web panel (yousong-web). Build context: the yousong-web repository (see Dockerfile.dockerignore: no
# .env.local, node_modules or .next enter the image).
#
# It runs the Next.js DEVELOPMENT server on purpose: the panel accepts plain HTTP only for loopback origins in
# development (production requires HTTPS for the app, the identity provider and the API), and this deployment is a
# local demo without certificates. It is reached only from this PC (http://localhost:3000).
FROM node:24-bookworm-slim
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm install --global bun@1.4.4 && npm cache clean --force
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --ignore-scripts
COPY . .
EXPOSE 3000
CMD ["node", "node_modules/next/dist/bin/next", "dev", "--hostname", "0.0.0.0", "--port", "3000"]
