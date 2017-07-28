# Iki - Jetty-Redis session management

This repository contains code required to install a new session store inside Jetty 9 servers (Redis store) and
a project illustrating this code with a very simple webapp (Java) deployed inside 2 Jetty servers and a load blancer
(Ha Proxy). Using your browser you 'll show response from the server picked up by the load balancer , proof of concept for session managed by the Redis container.

@author Pierre Raby (Zenika)
@author Jerome MOLIERE (Ucare)


Jetty -> app : Java code

Jetty -> app-web : conf JSX/XML

Jetty -> ext : jars needed by Jetty at startup

Build && Deploy (app) : `jetty/app/deploy.sh` && restart containers

Build && Deploy (app-web) : `jetty/app-web/deploy.sh`

Run : `docker-compose up`


## Important folders
The Jetty folder inside this repository contains the following files and folders:

![repository contents][jettyfolder]

[jettyfolder]:https://github.com/Zenika/iki-jetty-redis/raw/master/jetty_001.png

### app-web

#### webapps
A dummy Java web session demo. Useless except for the demo.  

#### ext
Jar files to be copied in the jetty lib/ext folder to achieve Redis session management.  

### app
This is the Java code required to store Jetty sessions inside Redis.  
Should be built and copied to Jetty server.  
As shown this code is very simple and does not involve much dependencies.  

## Testing

Deploying to production ready servers is far more simple:  
- compile with maven (mvn clean package) inside the app folder  
- grab the generated jar and copy into your Jetty lib/ext folder  
- copy the required jars into this folder too  

Have fun with sessions stored on Redis.  
As usual the redis-cli tools should be useful to show sessionss tored inside your Redis server.  

