# The console, built once and then served as files.
#
# Two stages because the result is static: everything the browser runs is produced here and
# nothing at runtime needs Node, so the image that ships is a web server and some assets.
FROM node:24-alpine AS builder
WORKDIR /src
# The lock file first, so that changing a component does not reinstall the world.
COPY console/package.json console/package-lock.json ./
RUN npm ci
COPY console/ ./
RUN npm run build

FROM nginx:1.29-alpine
# Same origin as the API on purpose. The refresh token is in a cookie scoped to
# /api/v1/tokens, and a browser only sends a cookie back to the origin that set it — so the
# console and the gateway have to look like one origin, which is what this proxy makes true.
COPY console/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /src/dist /usr/share/nginx/html
