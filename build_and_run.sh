#!/usr/bin/env bash
mvn clean package -DskipTests; java -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=8000 -jar target/compilview-1.0-SNAPSHOT-fat.jar -conf src/main/conf/my-application-conf.json

