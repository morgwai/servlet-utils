#!/bin/bash
# Copyright 2021 Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
if [[ -n "$(git status --porcelain)" ]]; then
	echo "repository not clean, exiting..." >&2;
	exit 1;
fi;

sed -E -e 's#(\t*).*<!--jakarta:(.*)-->#\1\2#' \
	-e 's#(.*)javax(.*)<!--jakarta-->#\1jakarta\2#' \
	<pom.xml >pom.jakarta.xml &&
mv pom.jakarta.xml pom.xml &&

find src -name '*.java' | while read file; do
	sed -e 's#javax.servlet#jakarta.servlet#g' \
		-e 's#javax.websocket#jakarta.websocket#g' \
		-e 's#org.eclipse.jetty.websocket.javax#org.eclipse.jetty.websocket.jakarta#g' \
		-e 's#JavaxWebSocket#JakartaWebSocket#g' \
		<"${file}" >"${file}.jakarta" &&
	mv "${file}.jakarta" "${file}";
done
