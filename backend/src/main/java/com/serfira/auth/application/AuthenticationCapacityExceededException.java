package com.serfira.auth.application;

import org.springframework.http.HttpStatus;

import com.serfira.shared.error.ErrorCode;

import com.serfira.shared.error.SerfiraException;

public class AuthenticationCapacityExceededException extends SerfiraException {

    public AuthenticationCapacityExceededException() {
        super(
            ErrorCode.AUTHENTICATION_CAPACITY_EXCEEDED,
            HttpStatus.SERVICE_UNAVAILABLE,
            "Authentication service is temporarily busy"
        );
    }
    
}
