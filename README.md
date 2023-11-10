# Servlet utils

Some helpful classes when developing `Servlet`s and websocket `Endpoint`s.<br/>
<br/>
**latest release: 4.0**<br/>
[javax flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/4.0-javax/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/4.0-javax)) - supports Websocket `1.1` API<br/>
[jakarta flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/4.0-jakarta/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/4.0-jakarta)) - supports Websocket `2.0.0` to at least `2.1.1` APIs


## MAIN USER CLASSES

For now just 1 class:
### [WebsocketPingerService](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/latest/pl/morgwai/base/servlet/utils/WebsocketPingerService.html)
Simple utility service that automatically pings and handles pongs from websocket connections. May be used both on a server and a client side. Supports round-trip time discovery.
