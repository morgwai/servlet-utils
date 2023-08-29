// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils.tests;

import jakarta.websocket.*;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static pl.morgwai.base.servlet.utils.EndpointUtils.*;



public class EndpointUtilsTests {



	@Test
	public void testExtendingEndpoint() throws NoSuchMethodException {
		final var onOpen = ExtendingEndpoint.class.getMethod(
				"onOpen", Session.class, EndpointConfig.class);
		final var onClose = ExtendingEndpoint.class.getMethod(
				"onClose", Session.class, CloseReason.class);
		final var someOtherMethod = ExtendingEndpoint.class.getMethod("someOtherMethod");
		final var fakeOnOpen = ExtendingEndpoint.class.getMethod("fakeOnOpen", Session.class);
		final var fakeOnClose = ExtendingEndpoint.class.getMethod("fakeOnClose", CloseReason.class);
		assertTrue(isOnClose(onClose));
		assertFalse(isOnClose(onOpen));
		assertFalse(isOnOpen(someOtherMethod));
		assertFalse(isOnClose(someOtherMethod));
		assertFalse(isOnError(someOtherMethod));
		assertFalse(isOnOpen(fakeOnOpen));
		assertFalse(isOnClose(fakeOnClose));
	}



	@Test
	public void testAnnotatedEndpoint() throws NoSuchMethodException {
		final var onOpen = AnnotatedEndpoint.class.getMethod(
				"onOpen", Session.class);
		final var onClose = AnnotatedEndpoint.class.getMethod(
				"onClose", CloseReason.class);
		final var someOtherMethod = AnnotatedEndpoint.class.getMethod("someOtherMethod");
		assertTrue(isOnOpen(onOpen));
		assertTrue(isOnClose(onClose));
		assertFalse(isOnClose(onOpen));
		assertFalse(isOnOpen(someOtherMethod));
		assertFalse(isOnClose(someOtherMethod));
		assertFalse(isOnError(someOtherMethod));
	}
}



class ExtendingEndpoint extends Endpoint {
	@Override public void onOpen(Session session, EndpointConfig config) {}
	@Override public void onClose(Session session, CloseReason closeReason) {}
	public void someOtherMethod() {}
	@OnOpen public void fakeOnOpen(Session session) {}
	@OnClose public void fakeOnClose(CloseReason closeReason) {}
}



class AnnotatedEndpoint {
	@OnOpen public void onOpen(Session session) {}
	@OnClose public void onClose(CloseReason closeReason) {}
	public void someOtherMethod() {}
}
