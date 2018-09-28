package com.edidat.module.ProxyRotator.pojo;

import com.edidat.module.ProxyRotator.Protocol;
import com.edidat.module.ProxyRotator.RequestType;

public class ClientRequestMessage {
	
	private RequestType requestType;
	private Protocol protocol;

	
	public RequestType getRequestType() {
		return requestType;
	}

	public void setRequestType(RequestType requestType) {
		this.requestType = requestType;
	}

	public Protocol getProtocol() {
		return protocol;
	}

	public void setProtocol(Protocol protocol) {
		this.protocol = protocol;
	}

	public ClientRequestMessage(Protocol protocol) {
		super();
		this.protocol = protocol;
	}

	public ClientRequestMessage(RequestType requestType, Protocol protocol) {
		super();
		this.requestType = requestType;
		this.protocol = protocol;
	}
	
	

}
