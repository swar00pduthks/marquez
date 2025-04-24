FROM node:18-alpine

WORKDIR /usr/src/app

ARG REACT_APP_ADVANCED_SEARCH

RUN apk update && apk add --virtual bash coreutils

RUN apk add --no-cache git

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