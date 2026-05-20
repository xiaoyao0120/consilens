package com.consilens.server.api.advice;

import com.consilens.server.api.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnBadRequestForMalformedJson() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])),
                request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void shouldReturnMethodNotAllowedForUnsupportedMethod() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodNotAllowed(
                new HttpRequestMethodNotSupportedException("PATCH"),
                request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getErrorCode()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "trace-test");
        return request;
    }
}
