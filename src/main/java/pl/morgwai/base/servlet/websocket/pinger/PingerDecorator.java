// Copyright (c) Piotr Morgwai Kotarbinski, Licensed under the Apache License, Version 2.0
package pl.morgwai.base.servlet.websocket.pinger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnOpen;
import javax.websocket.Session;



/**
 * For use with <code>GuiceServerEndpointConfigurator.getEndpointInvocationHandler(...)</code>
 * from <a href='https://github.com/morgwai/servlet-scopes'>servlet-scopes lib</a>.
 * @see <a href='https://github.com/morgwai/servlet-scopes/blob/master/src/main/java/pl/morgwai/
base/servlet/scopes/GuiceServerEndpointConfigurator.java'> GuiceServerEndpointConfigurator</a>
 */
public class PingerDecorator<T> implements InvocationHandler {



	T endpoint;
	WebsocketPinger pinger;

	Session connection;



	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (isOnOpen(method)) {
			for (int i = 0; i < args.length; i++) {
				if (args[i] instanceof Session) {
					connection = (Session) args[i];
					pinger.addConnection(connection);
				}
			}
		}
		if (isOnClose(method)) {
			pinger.removeConnection(connection);
		}
		return method.invoke(endpoint, args);
	}



	public static boolean isOnOpen(Method method) {
		return (
				method.getAnnotation(OnOpen.class) != null
				&& ! Endpoint.class.isAssignableFrom(method.getDeclaringClass())
			) || (
				Endpoint.class.isAssignableFrom(method.getDeclaringClass())
				&& method.getName().equals("onOpen")
				&& method.getParameterCount() == 2
				&& method.getParameterTypes()[0] == Session.class
				&& method.getParameterTypes()[1] == EndpointConfig.class
			);
	}



	public static boolean isOnClose(Method method) {
		return (
				method.getAnnotation(OnClose.class) != null
				&& ! Endpoint.class.isAssignableFrom(method.getDeclaringClass())
			) || (
				Endpoint.class.isAssignableFrom(method.getDeclaringClass())
				&& method.getName().equals("onClose")
				&& method.getParameterCount() == 2
				&& method.getParameterTypes()[0] == Session.class
				&& method.getParameterTypes()[1] == CloseReason.class
			);
	}



	public PingerDecorator(T endpoint, WebsocketPinger pinger) {
		this.endpoint = endpoint;
		this.pinger = pinger;
	}
}
