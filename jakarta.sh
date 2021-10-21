#!/bin/bash
# Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
sed -E -e 's#(\t*).*<!--jakarta:(.*)-->#\1\2#' \
	-e 's#^\t<version>(.*)-javax(.*)</version>#\t<version>\1-jakarta\2</version>#' \
	<pom.xml >pom.jakarta.xml &&
mv pom.jakarta.xml pom.xml &&

find src -name '*.java' | while read file; do
	sed -e 's#javax.servlet#jakarta.servlet#g' \
		-e 's#javax.websocket#jakarta.websocket#g' \
		-e 's#javax.persistence#jakarta.persistence#g' \
		<"${file}" >"${file}.jakarta" &&
	mv "${file}.jakarta" "${file}";
done
