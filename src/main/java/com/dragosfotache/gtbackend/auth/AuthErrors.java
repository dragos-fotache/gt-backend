package com.dragosfotache.gtbackend.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthErrors {
    @ExceptionHandler(AuthService.AuthException.class)
    ResponseEntity<?> auth(AuthService.AuthException e) {
        return ResponseEntity.status(e.status).cacheControl(CacheControl.noStore()).body(Map.of("errorCode", e.code));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    ResponseEntity<?> missingHeader(Exception e) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("errorCode", "invalid_request"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> safe(Exception e) {
        return ResponseEntity.status(500).cacheControl(CacheControl.noStore()).body(Map.of("errorCode", "internal_error"));
    }
}
