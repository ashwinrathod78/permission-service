package com.permision.service;

public class OperationCancelledException extends RuntimeException {
	public OperationCancelledException(String message) {
		super(message);
	}
}
