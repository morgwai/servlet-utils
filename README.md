# Servlet utils

Some helpful classes when developing `Servlet`s and websocket `Endpoint`s.<br/>
Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0.<br/>
<br/>
**latest release: 6.4**<br/>
[javax flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/6.4-javax/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/6.4-javax)) - supports Websocket `1.1` API<br/>
[jakarta flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/6.4-jakarta/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/6.4-jakarta)) - supports Websocket `2.0.0` to at least `2.1.1` APIs<br/>
<br/>
See [CHANGES](CHANGES.md) for the summary of changes between releases. If the major version of a subsequent release remains unchanged, it is supposed to be backwards compatible in terms of API and behaviour with previous ones with the same major version (meaning that it should be safe to just blindly update in dependent projects and things should not break under normal circumstances).


## MAIN USER CLASSES

For now just 1 class:
### [WebsocketPingerService](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/latest/pl/morgwai/base/servlet/utils/WebsocketPingerService.html)
Simple utility service that automatically pings and handles pongs from websocket connections. May be used both on a server and a client side. Supports round-trip time discovery.
