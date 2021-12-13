# Servlet utils

Some helpful classes when developing servlets.<br/>
<br/>
**latest release: 1.2**<br/>
[javax flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/1.2-javax/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/1.2-javax))<br/>
[jakarta flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/1.2-jakarta/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/1.2-jakarta))


## MAIN USER CLASSES

For now just 1 class: a simple utility service that automatically pings and handles pongs from websocket connections: [WebsocketPingerService](src/main/java/pl/morgwai/base/servlet/utils/WebsocketPingerService.java)


## USAGE

### Dependency management
Dependency of this jar on [slf4j-api](https://search.maven.org/artifact/org.slf4j/slf4j-api) is declared as optional, so that apps can use any version with compatible API.
