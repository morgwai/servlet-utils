// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.utils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import jakarta.websocket.*;



/** Some static helper functions related to {@link Endpoint}s. */
public interface EndpointUtils {



	/**
	 * Checks if {@code method} is either annotated with {@link OnOpen} or overrides
	 * {@link Endpoint#onOpen(Session, EndpointConfig)}.
	 */
	static boolean isOnOpen(Method method) {
		return isEndpointLifecycleMethod(method, OnOpen.class, "onOpen");
	}



	/**
	 * Checks if {@code method} is either annotated with {@link OnClose} or overrides
	 * {@link Endpoint#onClose(Session, CloseReason)}.
	 */
	static boolean isOnClose(Method method) {
		return isEndpointLifecycleMethod(method, OnClose.class, "onClose");
	}



	/**
	 * Checks if {@code method} is either annotated with {@link OnError} or overrides
	 * {@link Endpoint#onError(Session, Throwable)}.
	 */
	static boolean isOnError(Method method) {
		return isEndpointLifecycleMethod(method, OnError.class, "onError");
	}



	/**
	 * Checks if {@code method} either is annotated with {@code annotationClass} or overrides the
	 * {@link Endpoint} method given by {@code endpointMethodName}.
	 */
	private static boolean isEndpointLifecycleMethod(
		Method method,
		Class<? extends Annotation> annotationClass,
		String endpointMethodName
	) {
		if (
			method.isAnnotationPresent(annotationClass)
			&& !Endpoint.class.isAssignableFrom(method.getDeclaringClass())
		) {
			return true;
		}

		if ( !method.getName().equals(endpointMethodName)) return false;
		try {
			Endpoint.class.getMethod(endpointMethodName, method.getParameterTypes());
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}
}
