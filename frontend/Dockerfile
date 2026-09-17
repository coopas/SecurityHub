FROM node:18-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
# This stage only compiles the Angular bundle: `npm run build` is `ng build`, nothing else.
#
# The two browsers `npm ci` would download here add up to more than 400 MB in a layer that is
# thrown away straight afterwards, and neither of them is used in this image:
#  - puppeteer's Chromium exists to give karma a CHROME_BIN (see karma.conf.js), and the unit
#    suite runs in CI, not inside the container;
#  - the Cypress binary would not even run here — the image is Alpine (musl) and the binary is
#    glibc — and the end-to-end suite runs outside the container, against the running stack.
#
# This went unnoticed while the layer stayed cached; the first change to package.json
# invalidates it and both downloads come back in full.
ENV CYPRESS_INSTALL_BINARY=0
ENV PUPPETEER_SKIP_DOWNLOAD=1
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

FROM nginx:alpine
RUN apk add --no-cache wget
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/securityhub-frontend /usr/share/nginx/html

RUN chown -R nginx:nginx /usr/share/nginx/html /var/cache/nginx /var/log/nginx \
 && touch /var/run/nginx.pid \
 && chown nginx:nginx /var/run/nginx.pid

USER nginx
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
