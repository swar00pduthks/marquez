FROM node:20.20-alpine3.23

WORKDIR /usr/src/app

ARG REACT_APP_ADVANCED_SEARCH

# Update packages to fix OpenSSL vulnerabilities
RUN apk update && \
    apk upgrade && \
    apk add --no-cache bash coreutils git && \
    rm -rf /var/cache/apk/*

COPY package*.json ./

ENV REACT_APP_ADVANCED_SEARCH=$REACT_APP_ADVANCED_SEARCH

RUN npm install

COPY . .

RUN REACT_APP_ADVANCED_SEARCH=$REACT_APP_ADVANCED_SEARCH npm run build

# Create entrypoint script
RUN printf '#!/bin/sh\nset -e\nnode setupProxy.js\n' > /usr/src/app/entrypoint.sh && \
    chmod +x /usr/src/app/entrypoint.sh

EXPOSE 3000

ENTRYPOINT ["/usr/src/app/entrypoint.sh"]