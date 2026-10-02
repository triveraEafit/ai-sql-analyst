# syntax=docker/dockerfile:1

# ---- Build stage: install deps and produce the Next.js build ----------------
FROM node:20-alpine AS build
WORKDIR /app

# NEXT_PUBLIC_* values are inlined into the client bundle at build time, so they
# are build ARGs promoted to ENV before `npm run build`. Change -> rebuild.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ARG NEXT_PUBLIC_MAX_QUESTION_LENGTH=300
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_MAX_QUESTION_LENGTH=$NEXT_PUBLIC_MAX_QUESTION_LENGTH

# Install dependencies from the lockfile first (cached until the manifests change).
COPY package.json package-lock.json ./
RUN npm ci

# Build the production bundle.
COPY . .
RUN npm run build

# ---- Runtime stage: serve the production build as a non-root user -----------
FROM node:20-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production

# Only the artifacts needed to run `next start`.
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/.next ./.next
COPY --from=build /app/next.config.mjs ./next.config.mjs

# node:alpine ships an unprivileged `node` user.
USER node
EXPOSE 3000
CMD ["npm", "start"]