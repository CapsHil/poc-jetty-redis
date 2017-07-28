# Iki - Jetty-Redis session management

Jetty -> app : Java code

Jetty -> app-web : conf JSX/XML

Jetty -> ext : jars needed by Jetty at startup

Build && Deploy (app) : `jetty/app/deploy.sh` && restart containers

Build && Deploy (app-web) : `jetty/app-web/deploy.sh`

Run : `docker-compose up`
